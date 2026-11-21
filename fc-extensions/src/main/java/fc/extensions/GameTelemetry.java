package fc.extensions;

import fc.sim.StateHasher;
import fc.world.WorldState;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

/**
 * JMX 状态发布器：把当前局的关键指标注册到平台 MBeanServer。
 *
 * <p>注册名为 {@code fc.telemetry:type=Game}。调用方在每个命令/日推进后调用
 * {@link #update(WorldState)}；{@link #close()} 注销 MBean，避免测试或重启时
 * 残留旧实例。</p>
 *
 * <p>MBeanServer 在受限环境可能不可用，注册失败由调用方决定是否降级；
 * 本类不吞异常，保证问题可见。</p>
 */
public final class GameTelemetry implements GameTelemetryMBean, AutoCloseable {

    public static final String OBJECT_NAME = "fc.telemetry:type=Game";

    private volatile long day;
    private volatile String now = "";
    private volatile String stateHash = "";
    private volatile int locations;
    private volatile int ships;
    private volatile String credits = "0";
    private volatile String ending = "";
    private volatile int commandCount;

    private final MBeanServer server;
    private final ObjectName name;
    private volatile boolean closed;

    private GameTelemetry(MBeanServer server, ObjectName name) {
        this.server = server;
        this.name = name;
    }

    /** 在平台 MBeanServer 上注册；重复注册会抛 InstanceAlreadyExistsException。 */
    public static GameTelemetry register() throws JMException {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName(OBJECT_NAME);
        GameTelemetry telemetry = new GameTelemetry(server, name);
        server.registerMBean(telemetry, name);
        return telemetry;
    }

    /** 更新为世界状态的只读快照。 */
    public void update(WorldState state) {
        if (state == null) {
            return;
        }
        update(state.now().day(), state.now().toString(), StateHasher.hash(state),
                state.graph().placeCount(), state.graph().ships().size(),
                state.credits().stripTrailingZeros().toPlainString(),
                state.ending() == null ? "" : state.ending(), 0);
    }

    /** 更新全部指标（便于测试与外部采集器直接推送）。 */
    public void update(long day, String now, String stateHash, int locations, int ships,
                       String credits, String ending, int commandCount) {
        this.day = day;
        this.now = now == null ? "" : now;
        this.stateHash = stateHash == null ? "" : stateHash;
        this.locations = locations;
        this.ships = ships;
        this.credits = credits == null ? "0" : credits;
        this.ending = ending == null ? "" : ending;
        this.commandCount = commandCount;
    }

    public void setCommandCount(int commandCount) {
        this.commandCount = commandCount;
    }

    @Override
    public long getDay() {
        return day;
    }

    @Override
    public String getNow() {
        return now;
    }

    @Override
    public String getStateHash() {
        return stateHash;
    }

    @Override
    public int getLocations() {
        return locations;
    }

    @Override
    public int getShips() {
        return ships;
    }

    @Override
    public String getCredits() {
        return credits;
    }

    @Override
    public String getEnding() {
        return ending;
    }

    @Override
    public int getCommandCount() {
        return commandCount;
    }

    /** 注销 MBean；幂等。 */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
        } catch (JMException ignored) {
            // 注销失败（如服务器已关闭）不影响退出。
        }
    }
}
