package fc.extensions;

import fc.sim.StateHasher;
import fc.world.WorldState;

import javax.management.AttributeChangeNotification;
import javax.management.JMException;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanServer;
import javax.management.Notification;
import javax.management.NotificationBroadcasterSupport;
import javax.management.NotificationEmitter;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JMX 状态发布器：把当前局的关键指标注册到平台 MBeanServer。
 *
 * <p>注册名为 {@code fc.telemetry:type=Game}。调用方在每个命令/日推进后调用
 * {@link #update(WorldState)}；{@link #close()} 注销 MBean，避免测试或重启时
 * 残留旧实例。</p>
 *
 * <p>本类同时是 {@link NotificationEmitter}：状态哈希变化时广播
 * {@link AttributeChangeNotification}（类型 {@code jmx.attribute.change}），
 * 命令计数变化时广播 {@code fc.telemetry.command}，{@code userData} 带
 * 命令数与游戏日。监听方可按类型过滤，也可用 JMX 的 MBeanServer 远程订阅。</p>
 *
 * <p>MBeanServer 在受限环境可能不可用，注册失败由调用方决定是否降级；
 * 本类不吞异常，保证问题可见。</p>
 */
public final class GameTelemetry extends NotificationBroadcasterSupport
        implements GameTelemetryMBean, NotificationEmitter, AutoCloseable {

    public static final String OBJECT_NAME = "fc.telemetry:type=Game";

    /** 命令计数变化通知的类型。 */
    public static final String COMMAND_NOTIFICATION = "fc.telemetry.command";

    private static final MBeanNotificationInfo[] NOTIFICATION_INFO = {
            new MBeanNotificationInfo(
                    new String[] {AttributeChangeNotification.ATTRIBUTE_CHANGE},
                    AttributeChangeNotification.class.getName(),
                    "状态哈希等只读属性变化"),
            new MBeanNotificationInfo(
                    new String[] {COMMAND_NOTIFICATION},
                    Notification.class.getName(),
                    "重放命令计数变化（userData 含 commandCount 与 day）")
    };

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
    private final AtomicLong sequence = new AtomicLong();
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

    /** 更新为世界状态的只读快照；保留既有命令计数，避免误报归零。 */
    public void update(WorldState state) {
        if (state == null) {
            return;
        }
        update(state.now().day(), state.now().toString(), StateHasher.hash(state),
                state.graph().placeCount(), state.graph().ships().size(),
                state.credits().stripTrailingZeros().toPlainString(),
                state.ending() == null ? "" : state.ending(), commandCount);
    }

    /** 更新全部指标（便于测试与外部采集器直接推送）。 */
    public void update(long day, String now, String stateHash, int locations, int ships,
                       String credits, String ending, int commandCount) {
        String previousHash = this.stateHash;
        int previousCommands = this.commandCount;
        this.day = day;
        this.now = now == null ? "" : now;
        this.stateHash = stateHash == null ? "" : stateHash;
        this.locations = locations;
        this.ships = ships;
        this.credits = credits == null ? "0" : credits;
        this.ending = ending == null ? "" : ending;
        this.commandCount = commandCount;

        if (!Objects.equals(previousHash, this.stateHash)) {
            sendNotification(new AttributeChangeNotification(this,
                    sequence.incrementAndGet(), System.currentTimeMillis(),
                    "状态哈希变化", "StateHash", String.class.getName(),
                    previousHash, this.stateHash));
        }
        if (previousCommands != this.commandCount) {
            notifyCommands(this.commandCount);
        }
    }

    /** 只改命令计数；变化时广播 {@link #COMMAND_NOTIFICATION}。 */
    public void setCommandCount(int commandCount) {
        int previous = this.commandCount;
        this.commandCount = commandCount;
        if (previous != commandCount) {
            notifyCommands(commandCount);
        }
    }

    private void notifyCommands(int count) {
        Notification notification = new Notification(COMMAND_NOTIFICATION, this,
                sequence.incrementAndGet(), System.currentTimeMillis(),
                "命令计数 " + count);
        notification.setUserData(Map.of("commandCount", count, "day", day));
        sendNotification(notification);
    }

    /** 已广播的通知序号（含属性变化与命令计数）。 */
    public long notificationSequence() {
        return sequence.get();
    }

    @Override
    public MBeanNotificationInfo[] getNotificationInfo() {
        return NOTIFICATION_INFO.clone();
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
