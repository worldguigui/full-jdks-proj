package fc.extensions;

/**
 * JMX 只读 MBean 契约（标准 MBean 命名要求接口为 {@code 实现类名MBean}）。
 *
 * <p>运维/观战端可通过 {@code jconsole} 或 JMX 客户端读取当前局状态，无需
 * 打开游戏 REPL。字段均为只读，写操作仍只能经由游戏命令。</p>
 */
public interface GameTelemetryMBean {

    long getDay();

    String getNow();

    String getStateHash();

    int getLocations();

    int getShips();

    String getCredits();

    String getEnding();

    int getCommandCount();
}
