package fc.history.jdbc;

import fc.world.Chronicle;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 已注册的编年史会话。
 *
 * <p>JDBC URL 形如 {@code jdbc:fc:chronicle:<name>}。测试与 CLI 在查询期间绑定
 * 当前世界，查询结束后解绑；名称不参与世界状态，也不写入任何存档。</p>
 */
public final class ChronicleSessions {

    public static final String DEFAULT = "default";

    private static final Map<String, Chronicle> SESSIONS = new ConcurrentHashMap<>();
    private static final AtomicLong SEQ = new AtomicLong();

    private ChronicleSessions() {
    }

    /** 绑定一个命名会话；同名覆盖。 */
    public static void bind(String name, Chronicle chronicle) {
        SESSIONS.put(requireName(name), chronicle);
    }

    /** 绑定匿名会话，返回生成的名称。 */
    public static String bindAnonymous(Chronicle chronicle) {
        String name = "session" + SEQ.incrementAndGet();
        bind(name, chronicle);
        return name;
    }

    public static void unbind(String name) {
        SESSIONS.remove(requireName(name));
    }

    /** 解析会话；不存在时抛出 SQLException（JDBC 边界的错误类型）。 */
    public static Chronicle resolve(String name) throws SQLException {
        Chronicle chronicle = SESSIONS.get(requireName(name));
        if (chronicle == null) {
            throw new SQLException("未绑定编年史会话: " + name);
        }
        return chronicle;
    }

    /** 清除全部绑定（测试收尾）。 */
    public static void clear() {
        SESSIONS.clear();
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            return DEFAULT;
        }
        if (!name.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("会话名只允许字母、数字、下划线、点与短横线");
        }
        return name;
    }
}
