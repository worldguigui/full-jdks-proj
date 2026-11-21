package fc.history.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * 编年史只读 JDBC 驱动。
 *
 * <p>URL：{@code jdbc:fc:chronicle:<session>}；省略会话名时使用 {@code default}。
 * 会话必须先用 {@link ChronicleSessions#bind(String, fc.world.Chronicle)} 绑定。
 * 驱动通过 {@code META-INF/services/java.sql.Driver} 自动注册，也允许显式
 * {@link DriverManager#registerDriver(Driver)}。</p>
 */
public final class ChronicleDriver implements Driver {

    public static final String PREFIX = "jdbc:fc:chronicle";
    public static final String VERSION = "1.0";

    static {
        try {
            DriverManager.registerDriver(new ChronicleDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** 显式注册（服务加载不可用时使用）。 */
    public static void register() {
        // 静态初始化已完成注册；本方法只用于可读性与调用方意图表达。
    }

    /** 按会话名连接；供 DataSource 与测试复用。 */
    public static Connection connectTo(String session) throws SQLException {
        return JdbcProxies.connection(ChronicleSessions.resolve(session));
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        String session = sessionOf(url);
        return JdbcProxies.connection(ChronicleSessions.resolve(session));
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && (url.equals(PREFIX) || url.startsWith(PREFIX + ":"));
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        DriverPropertyInfo session = new DriverPropertyInfo("session",
                sessionOf(url));
        session.description = "已绑定的编年史会话名";
        session.required = false;
        return new DriverPropertyInfo[]{session};
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return Logger.getLogger("fc.history.jdbc");
    }

    private static String sessionOf(String url) {
        if (url == null || url.equals(PREFIX)) {
            return ChronicleSessions.DEFAULT;
        }
        String suffix = url.substring(PREFIX.length());
        if (suffix.startsWith(":")) {
            suffix = suffix.substring(1);
        }
        return suffix.isBlank() ? ChronicleSessions.DEFAULT : suffix;
    }
}
