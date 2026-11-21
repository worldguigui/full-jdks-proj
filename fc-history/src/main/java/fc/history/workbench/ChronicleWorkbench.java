package fc.history.workbench;

import fc.history.jdbc.ChronicleDriver;
import fc.history.jdbc.ChronicleSessions;
import fc.world.Faction;
import fc.world.Ids;
import fc.world.Location;
import fc.world.WorldState;

import javax.sql.RowSet;
import javax.sql.RowSetMetaData;
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.FilteredRowSet;
import javax.sql.rowset.JoinRowSet;
import javax.sql.rowset.Predicate;
import javax.sql.rowset.RowSetFactory;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import javax.sql.rowset.WebRowSet;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 离线编年史数据工作台：用 JDBC RowSet 把查询结果变成可脱离连接的本地结果集。
 *
 * <p>查询复用现有只读 JDBC 驱动（{@code jdbc:fc:chronicle}），随后由
 * {@link CachedRowSet#populate(ResultSet)} 读入内存；之后的过滤、排序、分页、
 * 与地点目录连接以及 XML 导出都不再需要连接数据源。</p>
 *
 * <p>导出的 XML 使用 JDBC {@link WebRowSet} 标准格式（UTF-8），并在每行附带
 * {@code schema_version=fc-workbench/1} 列；导入时校验列结构与版本，缺失或
 * 不兼容立即拒绝。</p>
 */
public final class ChronicleWorkbench {

    public static final List<String> COLUMNS = List.of(
            "day", "date", "type", "category", "location", "actors",
            "cause", "outcome", "summary");
    public static final String SCHEMA_COLUMN = "schema_version";
    public static final String SCHEMA_VERSION = "fc-workbench/1";

    private ChronicleWorkbench() {
    }

    /** 过滤条件；null/空串表示不限制，{@code *} 视为不限制。 */
    public record Filter(Long fromDay, Long toDay, String category, String type,
                         String location, String actor, String text) {
        public Filter {
            category = blankToNull(category);
            type = blankToNull(type);
            location = blankToNull(location);
            actor = blankToNull(actor);
            text = blankToNull(text);
        }

        public static Filter none() {
            return new Filter(null, null, null, null, null, null, null);
        }

        public boolean any() {
            return fromDay != null || toDay != null || category != null || type != null
                    || location != null || actor != null || text != null;
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() || "*".equals(value) ? null : value;
        }
    }

    private static RowSetFactory factory() throws SQLException {
        try {
            return RowSetProvider.newFactory();
        } catch (RuntimeException e) {
            throw new SQLException("当前 JDK 未提供 RowSet 实现：" + e.getMessage(), e);
        }
    }

    /** 从当前编年史装载离线结果集；连接在方法返回前关闭。 */
    public static CachedRowSet load(fc.world.Chronicle chronicle) throws SQLException {
        String session = ChronicleSessions.bindAnonymous(chronicle);
        String sql = "SELECT " + String.join(", ", COLUMNS)
                + " FROM chronicle ORDER BY day DESC";
        try (Connection connection = ChronicleDriver.connectTo(session);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            CachedRowSet rows = factory().createCachedRowSet();
            rows.populate(result);
            rows.setTableName("chronicle");
            return rows;
        } finally {
            ChronicleSessions.unbind(session);
        }
    }

    /** 离线过滤：结果与原连接无关，返回 FilteredRowSet。 */
    public static FilteredRowSet filter(CachedRowSet source, Filter filter)
            throws SQLException {
        FilteredRowSet filtered = factory().createFilteredRowSet();
        filtered.setFilter(new ChroniclePredicate(filter == null ? Filter.none() : filter));
        source.beforeFirst();
        filtered.populate(source);
        filtered.beforeFirst();
        return filtered;
    }

    /** 离线排序：复制为新 CachedRowSet，不改动传入结果集。 */
    public static CachedRowSet sort(CachedRowSet source, String column, boolean descending)
            throws SQLException {
        int index = columnIndex(source.getMetaData(), column);
        List<Object[]> rows = snapshot(source);
        Comparator<Object[]> comparator = (a, b) -> compareValues(a[index - 1], b[index - 1]);
        if (descending) {
            comparator = comparator.reversed();
        }
        rows.sort(comparator);
        CachedRowSet sorted = factory().createCachedRowSet();
        sorted.setMetaData(copyMetadata(source.getMetaData()));
        sorted.setTableName(source.getTableName());
        // JDK CachedRowSetImpl 的新行遍历顺序与插入顺序相反，按逆序写入保持目标顺序。
        insertAll(sorted, rows);
        return sorted;
    }

    /**
     * 与地点目录连接：事件 location 列（L&lt;id&gt;）与地点 id 列同键。
     * 返回的 JoinRowSet 仍可当作离线结果集遍历。
     */
    public static JoinRowSet joinLocations(CachedRowSet events, WorldState state)
            throws SQLException {
        CachedRowSet locations = locationCatalog(state);
        JoinRowSet joined = factory().createJoinRowSet();
        joined.addRowSet(events, "location");
        joined.addRowSet(locations, "id");
        return joined;
    }

    /** 生成地点目录（不依赖 JDBC/网络）。 */
    public static CachedRowSet locationCatalog(WorldState state) throws SQLException {
        CachedRowSet rows = factory().createCachedRowSet();
        RowSetMetaDataImpl meta = new RowSetMetaDataImpl();
        meta.setColumnCount(4);
        String[] names = {"id", "name", "kind", "owner"};
        int[] types = {java.sql.Types.VARCHAR, java.sql.Types.VARCHAR,
                java.sql.Types.VARCHAR, java.sql.Types.VARCHAR};
        for (int i = 0; i < names.length; i++) {
            meta.setColumnName(i + 1, names[i]);
            meta.setColumnLabel(i + 1, names[i]);
            meta.setColumnType(i + 1, types[i]);
        }
        rows.setMetaData(meta);
        rows.setTableName("locations");
        List<Location> places = new ArrayList<>();
        for (Location location : state.graph().nodes().values()) {
            if (!(location instanceof Location.Ship)) {
                places.add(location);
            }
        }
        places.sort(Comparator.comparingLong(location -> location.id().id()));
        List<Object[]> data = new ArrayList<>();
        for (Location place : places) {
            Ids.FactionId ownerId = state.locationOwner(place.id());
            Faction owner = ownerId == null ? null : state.faction(ownerId);
            data.add(new Object[] {"L" + place.id().id(), place.name(), kindOf(place),
                    owner == null ? "无主" : owner.name()});
        }
        insertAll(rows, data);
        rows.beforeFirst();
        return rows;
    }

    /** 把离线结果集复制为带版本列的 WebRowSet。 */
    public static WebRowSet toWebRowSet(CachedRowSet source) throws SQLException {
        WebRowSet web = factory().createWebRowSet();
        ResultSetMetaData sourceMeta = source.getMetaData();
        int count = sourceMeta.getColumnCount();
        RowSetMetaDataImpl meta = copyMetadata(sourceMeta, count + 1);
        meta.setColumnName(count + 1, SCHEMA_COLUMN);
        meta.setColumnLabel(count + 1, SCHEMA_COLUMN);
        meta.setColumnType(count + 1, java.sql.Types.VARCHAR);
        web.setMetaData(meta);
        web.setTableName("chronicle");
        List<Object[]> rows = snapshot(source);
        List<Object[]> withVersion = new ArrayList<>();
        for (Object[] row : rows) {
            Object[] extended = java.util.Arrays.copyOf(row, count + 1);
            extended[count] = SCHEMA_VERSION;
            withVersion.add(extended);
        }
        insertAll(web, withVersion);
        web.beforeFirst();
        return web;
    }

    /** 导出 WebRowSet XML（UTF-8 字节流），创建父目录。 */
    public static Path exportXml(CachedRowSet rows, Path output) throws SQLException, IOException {
        Path absolute = output.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        WebRowSet web = toWebRowSet(rows);
        try (OutputStream out = Files.newOutputStream(absolute)) {
            web.writeXml(out);
        }
        return absolute;
    }

    /** 从 XML 恢复 WebRowSet，并校验字段结构与版本列。 */
    public static WebRowSet importXml(Path input) throws SQLException, IOException {
        if (!Files.isRegularFile(input)) {
            throw new SQLException("工作台文件不存在：" + input);
        }
        WebRowSet web = factory().createWebRowSet();
        try (InputStream in = Files.newInputStream(input)) {
            web.readXml(in);
        } catch (SQLException | RuntimeException e) {
            throw new SQLException("不是合法的 WebRowSet XML：" + e.getMessage(), e);
        }
        ResultSetMetaData meta = web.getMetaData();
        if (meta == null) {
            // JDK 的 WebRowSet.readXml 对结构不符的 XML 不总是抛异常，元数据可能是 null。
            throw new SQLException("不是合法的 WebRowSet XML：缺少元数据");
        }
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            names.add(meta.getColumnName(i).toLowerCase(Locale.ROOT));
        }
        for (String required : COLUMNS) {
            if (!names.contains(required)) {
                throw new SQLException("工作台文件缺少列：" + required);
            }
        }
        if (!names.contains(SCHEMA_COLUMN)) {
            throw new SQLException("工作台文件缺少版本列：" + SCHEMA_COLUMN);
        }
        int index = names.indexOf(SCHEMA_COLUMN) + 1;
        web.beforeFirst();
        int rows = 0;
        while (web.next()) {
            String version = web.getString(index);
            if (!SCHEMA_VERSION.equals(version)) {
                throw new SQLException("不兼容的工作台格式版本：" + version
                        + "（需要 " + SCHEMA_VERSION + "）");
            }
            rows++;
        }
        if (rows == 0) {
            throw new SQLException("工作台文件没有数据行");
        }
        web.beforeFirst();
        return web;
    }

    /** 分页渲染离线结果集；页码从 1 开始。 */
    public static String render(CachedRowSet rows, int page, int pageSize)
            throws SQLException {
        int size = count(rows);
        int perPage = Math.max(1, Math.min(200, pageSize));
        int pages = Math.max(1, (size + perPage - 1) / perPage);
        int current = Math.max(1, Math.min(pages, page));
        StringBuilder out = new StringBuilder();
        out.append("---- 离线编年史 第 ").append(current).append('/').append(pages)
                .append(" 页（共 ").append(size).append(" 行）----\n  ");
        ResultSetMetaData meta = rows.getMetaData();
        int columns = meta.getColumnCount();
        for (int i = 1; i <= columns; i++) {
            if (i > 1) {
                out.append(" | ");
            }
            out.append(meta.getColumnLabel(i));
        }
        out.append('\n');
        rows.beforeFirst();
        int skipped = (current - 1) * perPage;
        for (int i = 0; i < skipped && rows.next(); i++) {
            // 跳过前页。
        }
        int printed = 0;
        while (printed < perPage && rows.next()) {
            out.append("  ");
            for (int i = 1; i <= columns; i++) {
                if (i > 1) {
                    out.append(" | ");
                }
                String value = rows.getString(i);
                out.append(value == null ? "NULL" : truncate(value, 40));
            }
            out.append('\n');
            printed++;
        }
        return out.toString();
    }

    /** 统计页数。 */
    public static int pageCount(CachedRowSet rows, int pageSize) throws SQLException {
        int perPage = Math.max(1, Math.min(200, pageSize));
        return Math.max(1, (count(rows) + perPage - 1) / perPage);
    }

    /** 实际可遍历行数（FilteredRowSet 的 size() 不反映过滤结果）。 */
    public static int count(CachedRowSet rows) throws SQLException {
        int count = 0;
        rows.beforeFirst();
        while (rows.next()) {
            count++;
        }
        rows.beforeFirst();
        return count;
    }

    // ---- 内部 ----

    private static RowSetMetaDataImpl copyMetadata(ResultSetMetaData source)
            throws SQLException {
        return copyMetadata(source, source.getColumnCount());
    }

    private static RowSetMetaDataImpl copyMetadata(ResultSetMetaData source, int columns)
            throws SQLException {
        RowSetMetaDataImpl meta = new RowSetMetaDataImpl();
        meta.setColumnCount(columns);
        for (int i = 1; i <= source.getColumnCount(); i++) {
            meta.setColumnName(i, source.getColumnName(i));
            meta.setColumnLabel(i, source.getColumnLabel(i));
            meta.setColumnType(i, source.getColumnType(i));
        }
        return meta;
    }

    private static List<Object[]> snapshot(CachedRowSet source) throws SQLException {
        ResultSetMetaData meta = source.getMetaData();
        int columns = meta.getColumnCount();
        List<Object[]> rows = new ArrayList<>();
        source.beforeFirst();
        while (source.next()) {
            Object[] row = new Object[columns];
            for (int i = 0; i < columns; i++) {
                row[i] = source.getObject(i + 1);
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * 逆序写入：JDK CachedRowSetImpl 新建行的遍历顺序与插入顺序相反，
     * 逆序插入才能让调用方看到预期顺序。
     */
    private static void insertAll(CachedRowSet target, List<Object[]> rows)
            throws SQLException {
        for (int r = rows.size() - 1; r >= 0; r--) {
            Object[] row = rows.get(r);
            target.moveToInsertRow();
            for (int i = 0; i < row.length; i++) {
                target.updateObject(i + 1, row[i]);
            }
            target.insertRow();
            target.moveToCurrentRow();
        }
    }

    private static int columnIndex(ResultSetMetaData meta, String column) throws SQLException {
        String wanted = column == null ? "" : column.strip().toLowerCase(Locale.ROOT);
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            if (meta.getColumnLabel(i).equalsIgnoreCase(wanted)
                    || meta.getColumnName(i).equalsIgnoreCase(wanted)) {
                return i;
            }
        }
        throw new SQLException("未知列：" + column + "（可选 " + COLUMNS + "）");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareValues(Object a, Object b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        if (a instanceof Comparable left && a.getClass().isInstance(b)) {
            return left.compareTo(b);
        }
        return String.valueOf(a).compareToIgnoreCase(String.valueOf(b));
    }

    private static String kindOf(Location location) {
        return switch (location) {
            case Location.StarSystem s -> "恒星系";
            case Location.Planet p -> "行星";
            case Location.OrbitalStation s -> "空间站";
            case Location.Anomaly a -> "异常点";
            case Location.Colony c -> "殖民地";
            case Location.Facility f -> "设施";
            case Location.Ship s -> "舰船";
            case Location.ExtensionLocation e -> "扩展地点";
        };
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** 行集过滤谓词：按日区间、类别、类型、地点、参与主体与全文匹配。 */
    private static final class ChroniclePredicate implements Predicate {
        private final Filter filter;

        ChroniclePredicate(Filter filter) {
            this.filter = filter;
        }

        @Override
        public boolean evaluate(RowSet rowSet) {
            try {
                java.sql.ResultSet rows = (java.sql.ResultSet) rowSet;
                if (filter.fromDay() != null && rows.getLong("day") < filter.fromDay()) {
                    return false;
                }
                if (filter.toDay() != null && rows.getLong("day") > filter.toDay()) {
                    return false;
                }
                return matches(rows.getString("category"), filter.category())
                        && matches(rows.getString("type"), filter.type())
                        && matches(rows.getString("location"), filter.location())
                        && contains(rows.getString("actors"), filter.actor())
                        && contains(rows.getString("summary"), filter.text());
            } catch (SQLException e) {
                return false;
            }
        }

        @Override
        public boolean evaluate(Object value, int column) {
            return true;
        }

        @Override
        public boolean evaluate(Object value, String columnName) {
            return true;
        }

        private static boolean matches(String value, String wanted) {
            return wanted == null || (value != null && value.equalsIgnoreCase(wanted));
        }

        private static boolean contains(String value, String wanted) {
            return wanted == null || (value != null
                    && value.toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT)));
        }
    }
}
