package fc.history.jdbc;

import fc.world.Chronicle;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * 只读 JDBC 接口的动态代理实现。
 *
 * <p>JDBC 的 {@link ResultSet}、{@link Connection} 等接口各有上百个方法，但本驱动只需要
 * 一小部分语义。用 {@link Proxy} 把已实现的读取、分页与元数据方法分派到真实逻辑，
 * 其余方法统一抛出 {@link SQLFeatureNotSupportedException}——比伪造返回值更诚实，
 * 也让驱动的能力边界在运行时可见。</p>
 *
 * <p>该实现不是通用数据库：没有写入、事务、游标滚动或连接池。</p>
 */
public final class JdbcProxies {

    private JdbcProxies() {
    }

    static Connection connection(Chronicle chronicle) {
        return (Connection) Proxy.newProxyInstance(
                JdbcProxies.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new ConnectionHandler(chronicle));
    }

    static ResultSet resultSet(ChronicleSql.QueryResult result, Statement statement) {
        return (ResultSet) Proxy.newProxyInstance(
                JdbcProxies.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                new ResultSetHandler(result, statement));
    }

    private static ResultSet emptyResult(String... columns) {
        List<ChronicleSql.ColumnMeta> metas = new ArrayList<>();
        for (String column : columns) {
            metas.add(new ChronicleSql.ColumnMeta(column, Types.VARCHAR,
                    "VARCHAR", String.class.getName()));
        }
        return resultSet(new ChronicleSql.QueryResult(metas, List.of()), null);
    }

    // ------------------------------------------------------------ Connection

    private static final class ConnectionHandler implements InvocationHandler {
        private final Chronicle chronicle;
        private Connection self;
        private boolean autoCommit = true;
        private boolean closed;

        private ConnectionHandler(Chronicle chronicle) {
            this.chronicle = chronicle;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (self == null) {
                self = (Connection) proxy;
            }
            String name = method.getName();
            switch (name) {
                case "toString":
                    return "fc.history.jdbc.ChronicleConnection";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "createStatement":
                    ensureOpen();
                    return statement(null);
                case "prepareStatement":
                    ensureOpen();
                    return prepared((String) args[0]);
                case "getMetaData":
                    ensureOpen();
                    return databaseMetaData();
                case "setAutoCommit":
                    autoCommit = (Boolean) args[0];
                    return null;
                case "getAutoCommit":
                    return autoCommit;
                case "commit", "rollback", "clearWarnings", "setReadOnly",
                        "setClientInfo", "setCatalog", "setSchema", "abort":
                    return null;
                case "close":
                    closed = true;
                    return null;
                case "isClosed":
                    return closed;
                case "isValid":
                    return !closed;
                case "getWarnings":
                    return null;
                case "getCatalog":
                case "getSchema":
                    return null;
                case "getTransactionIsolation":
                    return Connection.TRANSACTION_NONE;
                case "setTransactionIsolation":
                    if ((Integer) args[0] != Connection.TRANSACTION_NONE) {
                        throw new SQLFeatureNotSupportedException("只读驱动不支持事务隔离级别");
                    }
                    return null;
                case "isReadOnly":
                    return true;
                case "getHoldability":
                case "setHoldability":
                    return ResultSet.CLOSE_CURSORS_AT_COMMIT;
                case "nativeSQL":
                    return args[0];
                case "getTypeMap":
                    return Map.of();
                case "setTypeMap":
                    return null;
                case "getClientInfo":
                    return new Properties();
                case "isWrapperFor":
                    return ((Class<?>) args[0]).isInstance(proxy);
                case "unwrap":
                    if (((Class<?>) args[0]).isInstance(proxy)) {
                        return proxy;
                    }
                    throw new SQLException("不是目标类型: " + args[0]);
                default:
                    return unsupported(method);
            }
        }

        private Statement statement(String sql) {
            return (Statement) Proxy.newProxyInstance(
                    JdbcProxies.class.getClassLoader(),
                    new Class<?>[]{sql == null ? Statement.class : PreparedStatement.class},
                    new StatementHandler(chronicle, self, sql, sql != null));
        }

        private PreparedStatement prepared(String sql) {
            return (PreparedStatement) Proxy.newProxyInstance(
                    JdbcProxies.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    new StatementHandler(chronicle, self, sql, true));
        }

        private DatabaseMetaData databaseMetaData() {
            return (DatabaseMetaData) Proxy.newProxyInstance(
                    JdbcProxies.class.getClassLoader(),
                    new Class<?>[]{DatabaseMetaData.class},
                    new DbMetaHandler(self));
        }

        private void ensureOpen() throws SQLException {
            if (closed) {
                throw new SQLException("连接已关闭");
            }
        }
    }

    // ------------------------------------------------------------ Statement

    private static final class StatementHandler implements InvocationHandler {
        private final Chronicle chronicle;
        private final Connection connection;
        private final String sql;
        private final boolean prepared;
        private final Map<Integer, Object> params = new TreeMap<>();
        private int maxRows;
        private int fetchSize;
        private int queryTimeout;
        private boolean closed;
        private ResultSet resultSet;

        private StatementHandler(Chronicle chronicle, Connection connection,
                                 String sql, boolean prepared) {
            this.chronicle = chronicle;
            this.connection = connection;
            this.sql = sql;
            this.prepared = prepared;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            switch (name) {
                case "toString":
                    return prepared ? "ChroniclePreparedStatement" : "ChronicleStatement";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "executeQuery":
                    ensureOpen();
                    String query = prepared ? sql : (String) args[0];
                    resultSet = resultSet(ChronicleSql.execute(chronicle, query,
                            parameterList()), (Statement) proxy);
                    return resultSet;
                case "execute":
                    executeQueryInternal(proxy, prepared ? sql : (String) args[0]);
                    return true;
                case "getResultSet":
                    return resultSet;
                case "getUpdateCount":
                    return -1;
                case "getMoreResults":
                    return false;
                case "executeUpdate", "executeLargeUpdate", "executeBatch":
                    throw new SQLFeatureNotSupportedException("编年史驱动是只读的");
                case "close":
                    closed = true;
                    return null;
                case "isClosed":
                    return closed;
                case "getConnection":
                    return connection;
                case "setMaxRows":
                    maxRows = (Integer) args[0];
                    return null;
                case "getMaxRows":
                    return maxRows;
                case "setFetchSize":
                    fetchSize = (Integer) args[0];
                    return null;
                case "getFetchSize":
                    return fetchSize;
                case "setQueryTimeout":
                    queryTimeout = (Integer) args[0];
                    return null;
                case "getQueryTimeout":
                    return queryTimeout;
                case "getResultSetType":
                    return ResultSet.TYPE_FORWARD_ONLY;
                case "getResultSetConcurrency":
                    return ResultSet.CONCUR_READ_ONLY;
                case "getResultSetHoldability":
                    return ResultSet.CLOSE_CURSORS_AT_COMMIT;
                case "getWarnings":
                    return null;
                case "clearWarnings", "setEscapeProcessing", "cancel",
                        "setFetchDirection", "closeOnCompletion":
                    return null;
                case "getFetchDirection":
                    return ResultSet.FETCH_FORWARD;
                case "isCloseOnCompletion":
                    return false;
                case "setPoolable", "setCursorName":
                    return null;
                case "isPoolable":
                    return false;
                case "getGeneratedKeys":
                    throw new SQLFeatureNotSupportedException("没有生成键");
                case "setObject":
                    requirePrepared();
                    params.put((Integer) args[0], args[1]);
                    return null;
                case "setString":
                case "setInt":
                case "setLong":
                case "setShort":
                case "setByte":
                case "setFloat":
                case "setDouble":
                case "setBoolean":
                case "setBigDecimal":
                case "setDate":
                case "setTime":
                case "setTimestamp":
                    requirePrepared();
                    params.put((Integer) args[0], args.length > 2 ? args[1] : args[1]);
                    return null;
                case "setNull":
                    requirePrepared();
                    params.put((Integer) args[0], null);
                    return null;
                case "clearParameters":
                    params.clear();
                    return null;
                case "addBatch", "clearBatch":
                    return null;
                case "isWrapperFor":
                    return ((Class<?>) args[0]).isInstance(proxy);
                case "unwrap":
                    if (((Class<?>) args[0]).isInstance(proxy)) {
                        return proxy;
                    }
                    throw new SQLException("不是目标类型: " + args[0]);
                default:
                    return unsupported(method);
            }
        }

        private void executeQueryInternal(Object proxy, String query) throws SQLException {
            ensureOpen();
            resultSet = resultSet(ChronicleSql.execute(chronicle, query,
                    parameterList()), (Statement) proxy);
        }

        private List<Object> parameterList() throws SQLException {
            if (!prepared) {
                return List.of();
            }
            int count = 0;
            boolean quote = false;
            for (char c : sql.toCharArray()) {
                if (c == '\'') {
                    quote = !quote;
                } else if (c == '?' && !quote) {
                    count++;
                }
            }
            List<Object> values = new ArrayList<>(count);
            for (int i = 1; i <= count; i++) {
                if (!params.containsKey(i)) {
                    throw new SQLException("参数 " + i + " 未设置");
                }
                values.add(params.get(i));
            }
            return values;
        }

        private void requirePrepared() throws SQLException {
            if (!prepared) {
                throw new SQLFeatureNotSupportedException("普通 Statement 不支持参数");
            }
        }

        private void ensureOpen() throws SQLException {
            if (closed) {
                throw new SQLException("语句已关闭");
            }
        }
    }

    // ------------------------------------------------------------ ResultSet

    private static final class ResultSetHandler implements InvocationHandler {
        private final ChronicleSql.QueryResult result;
        private final Statement statement;
        private int cursor = -1;
        private boolean closed;
        private boolean wasNull;

        private ResultSetHandler(ChronicleSql.QueryResult result, Statement statement) {
            this.result = result;
            this.statement = statement;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            switch (name) {
                case "toString":
                    return "ChronicleResultSet(" + result.rows().size() + " 行)";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "next":
                    cursor++;
                    return cursor < result.rows().size();
                case "close":
                    closed = true;
                    return null;
                case "isClosed":
                    return closed;
                case "getRow":
                    return isValid() ? cursor + 1 : 0;
                case "getMetaData":
                    return metadata();
                case "findColumn":
                    return findColumn(String.valueOf(args[0]));
                case "wasNull":
                    return wasNull;
                case "getString":
                    return asString(value(args));
                case "getInt":
                    return asNumber(args).intValue();
                case "getLong":
                    return asNumber(args).longValue();
                case "getShort":
                    return asNumber(args).shortValue();
                case "getByte":
                    return asNumber(args).byteValue();
                case "getFloat":
                    return asNumber(args).floatValue();
                case "getDouble":
                    return asNumber(args).doubleValue();
                case "getBigDecimal":
                    return new BigDecimal(asString(value(args)));
                case "getBoolean":
                    String bool = asString(value(args));
                    return "true".equalsIgnoreCase(bool) || "1".equals(bool);
                case "getDate":
                    return Date.valueOf(asString(value(args)));
                case "getTime":
                    return Time.valueOf("00:00:00");
                case "getTimestamp":
                    return Timestamp.valueOf(asString(value(args)) + " 00:00:00");
                case "getObject":
                    return value(args);
                case "getStatement":
                    return statement;
                case "getType":
                    return ResultSet.TYPE_FORWARD_ONLY;
                case "getConcurrency":
                    return ResultSet.CONCUR_READ_ONLY;
                case "getHoldability":
                    return ResultSet.CLOSE_CURSORS_AT_COMMIT;
                case "getFetchDirection":
                    return ResultSet.FETCH_FORWARD;
                case "getFetchSize":
                    return 0;
                case "isBeforeFirst":
                    return cursor < 0 && !result.rows().isEmpty();
                case "isAfterLast":
                    return cursor >= result.rows().size() && !result.rows().isEmpty();
                case "isFirst":
                    return cursor == 0 && !result.rows().isEmpty();
                case "isLast":
                    return cursor == result.rows().size() - 1 && isValid();
                case "getWarnings":
                    return null;
                case "clearWarnings":
                    return null;
                case "isWrapperFor":
                    return ((Class<?>) args[0]).isInstance(proxy);
                case "unwrap":
                    if (((Class<?>) args[0]).isInstance(proxy)) {
                        return proxy;
                    }
                    throw new SQLException("不是目标类型: " + args[0]);
                default:
                    return unsupported(method);
            }
        }

        private ResultSetMetaData metadata() {
            return (ResultSetMetaData) Proxy.newProxyInstance(
                    JdbcProxies.class.getClassLoader(),
                    new Class<?>[]{ResultSetMetaData.class},
                    new MetaHandler(result));
        }

        private int findColumn(String label) throws SQLException {
            for (int i = 0; i < result.columns().size(); i++) {
                if (result.columns().get(i).name().equalsIgnoreCase(label)) {
                    return i + 1;
                }
            }
            throw new SQLException("未知列标签: " + label);
        }

        private Object value(Object[] args) throws SQLException {
            ensureValid();
            int index = args[0] instanceof Integer i ? i - 1 : findColumn((String) args[0]) - 1;
            if (index < 0 || index >= result.columns().size()) {
                throw new SQLException("列索引越界: " + (index + 1));
            }
            Object value = result.rows().get(cursor)[index];
            wasNull = value == null;
            return value;
        }

        private String asString(Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof Date || value instanceof Time
                    || value instanceof Timestamp) {
                return value.toString();
            }
            return String.valueOf(value);
        }

        private Number asNumber(Object[] args) throws SQLException {
            String text = asString(value(args));
            if (text == null || text.isBlank()) {
                return 0;
            }
            try {
                return new BigDecimal(text.strip());
            } catch (NumberFormatException e) {
                throw new SQLException("列值不是数字: " + text);
            }
        }

        private boolean isValid() {
            return cursor >= 0 && cursor < result.rows().size();
        }

        private void ensureValid() throws SQLException {
            if (closed) {
                throw new SQLException("结果集已关闭");
            }
            if (!isValid()) {
                throw new SQLException("结果集未定位到有效行");
            }
        }
    }

    // ------------------------------------------------------------ Metadata

    private static final class MetaHandler implements InvocationHandler {
        private final ChronicleSql.QueryResult result;

        private MetaHandler(ChronicleSql.QueryResult result) {
            this.result = result;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("toString")) {
                return "ChronicleResultSetMetaData(" + result.columns().size() + " 列)";
            }
            if (name.equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            if (name.equals("equals")) {
                return proxy == args[0];
            }
            switch (name) {
                case "getColumnCount":
                    return result.columns().size();
                case "getColumnName":
                case "getColumnLabel":
                    return column(args).name();
                case "getColumnType":
                    return column(args).jdbcType();
                case "getColumnTypeName":
                    return column(args).typeName();
                case "getColumnClassName":
                    return column(args).className();
                case "getTableName":
                    return "chronicle";
                case "getSchemaName":
                case "getCatalogName":
                    return "";
                case "isNullable":
                    return ResultSetMetaData.columnNullable;
                case "getPrecision":
                    return column(args).jdbcType() == Types.BIGINT ? 19 : 255;
                case "getScale":
                    return 0;
                case "getColumnDisplaySize":
                    return column(args).jdbcType() == Types.BIGINT ? 19 : 64;
                case "isSigned":
                    return column(args).jdbcType() != Types.VARCHAR;
                case "isCaseSensitive":
                    return column(args).jdbcType() == Types.VARCHAR;
                case "isSearchable":
                case "isReadOnly":
                    return true;
                case "isAutoIncrement":
                case "isCurrency":
                case "isWritable":
                case "isDefinitelyWritable":
                    return false;
                case "isWrapperFor":
                    return ((Class<?>) args[0]).isInstance(proxy);
                case "unwrap":
                    if (((Class<?>) args[0]).isInstance(proxy)) {
                        return proxy;
                    }
                    throw new SQLException("不是目标类型: " + args[0]);
                default:
                    return unsupported(method);
            }
        }

        private ChronicleSql.ColumnMeta column(Object[] args) throws SQLException {
            int index = (Integer) args[0] - 1;
            if (index < 0 || index >= result.columns().size()) {
                throw new SQLException("列索引越界: " + (index + 1));
            }
            return result.columns().get(index);
        }
    }

    private static final class DbMetaHandler implements InvocationHandler {
        private final Connection connection;

        private DbMetaHandler(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("toString")) {
                return "ChronicleDatabaseMetaData";
            }
            if (name.equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            if (name.equals("equals")) {
                return proxy == args[0];
            }
            if (name.startsWith("supports")) {
                return false;
            }
            switch (name) {
                case "getDatabaseProductName":
                    return "Frontier Chronicle";
                case "getDatabaseProductVersion":
                    return "0.6";
                case "getDriverName":
                    return "Frontier Chronicle JDBC";
                case "getDriverVersion":
                    return "1.0";
                case "getDriverMajorVersion":
                    return 1;
                case "getDriverMinorVersion":
                    return 0;
                case "getURL":
                    return "jdbc:fc:chronicle";
                case "getUserName":
                    return "captain";
                case "isReadOnly":
                    return true;
                case "getDefaultTransactionIsolation":
                    return Connection.TRANSACTION_NONE;
                case "getConnection":
                    return connection;
                case "getTables":
                    return emptyResult("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME",
                            "TABLE_TYPE", "REMARKS");
                case "getColumns":
                    return emptyResult("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME",
                            "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME");
                case "getCatalogs", "getSchemas", "getTableTypes", "getProcedures",
                        "getFunctions", "getPrimaryKeys":
                    return emptyResult("NAME");
                case "getIdentifierQuoteString":
                    return "\"";
                case "getSQLKeywords", "getExtraNameCharacters":
                    return "";
                case "getSearchStringEscape":
                    return "\\";
                case "getJDBCMajorVersion":
                    return 4;
                case "getJDBCMinorVersion":
                    return 3;
                case "getResultSetHoldability":
                    return ResultSet.CLOSE_CURSORS_AT_COMMIT;
                case "getSQLStateType":
                    return DatabaseMetaData.sqlStateSQL;
                case "getMaxConnections", "getMaxStatementLength":
                    return 0;
                case "getDatabaseMajorVersion":
                    return 0;
                case "getDatabaseMinorVersion":
                    return 6;
                case "isWrapperFor":
                    return ((Class<?>) args[0]).isInstance(proxy);
                case "unwrap":
                    if (((Class<?>) args[0]).isInstance(proxy)) {
                        return proxy;
                    }
                    throw new SQLException("不是目标类型: " + args[0]);
                default:
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type == int.class) {
                        return 0;
                    }
                    if (type == long.class) {
                        return 0L;
                    }
                    if (type == String.class) {
                        return "";
                    }
                    return unsupported(method);
            }
        }
    }

    /** 未实现方法的统一出口：按返回类型给空值，调用方拿不到伪数据时会显式失败。 */
    private static Object unsupported(Method method) throws SQLException {
        throw new SQLFeatureNotSupportedException(
                "JDBC 驱动未实现: " + method.getDeclaringClass().getSimpleName()
                        + "." + method.getName());
    }

    /** 供 DataSource 使用的元数据列（保持接口完整）。 */
    static Map<String, Integer> knownColumns() {
        Map<String, Integer> columns = new LinkedHashMap<>();
        columns.put("day", Types.BIGINT);
        for (String name : List.of("date", "type", "category", "location",
                "actors", "cause", "outcome", "summary")) {
            columns.put(name, Types.VARCHAR);
        }
        return columns;
    }

    /** 小写化工具（保持 SQL 标识符语义一致）。 */
    static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
