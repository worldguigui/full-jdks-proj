package fc.history.jdbc;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * 编年史的 {@link DataSource}：按会话名返回只读连接。
 *
 * <p>这不是连接池；每次 {@code getConnection()} 都创建一个无状态代理连接。
 * 适合工具与测试按标准 JDBC 入口获取查询连接。</p>
 */
public final class ChronicleDataSource implements DataSource {

    private final String session;
    private PrintWriter logWriter;
    private int loginTimeout;

    public ChronicleDataSource(String session) {
        if (session == null || session.isBlank()) {
            throw new IllegalArgumentException("会话名不能为空");
        }
        if (!session.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("会话名只允许字母、数字、下划线、点与短横线");
        }
        this.session = session;
    }

    public String session() {
        return session;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return ChronicleDriver.connectTo(session);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) {
        if (seconds < 0) {
            throw new IllegalArgumentException("登录超时不得为负");
        }
        this.loginTimeout = seconds;
    }

    @Override
    public int getLoginTimeout() {
        return loginTimeout;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return Logger.getLogger("fc.history.jdbc");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("不是目标类型: " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
