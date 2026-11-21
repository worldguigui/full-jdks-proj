package fc.extensions;

import javax.management.InstanceNotFoundException;
import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.timer.Timer;
import java.lang.management.ManagementFactory;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 真实时间心跳：用 {@link Timer} MBean 周期广播 {@code fc.heartbeat} 通知。
 *
 * <p>游戏时间是离散的 Tick，真实时间运维信号则由 JMX 定时器驱动；两者分离，
 * 心跳不会推进世界状态，也不进入重放日志。注册名
 * {@code fc.telemetry:type=Heartbeat}；{@link #beats()} 返回 Timer 已触发的
 * 次数，便于 {@code jmx heartbeat status} 展示。</p>
 */
public final class Heartbeat implements AutoCloseable {

    public static final String OBJECT_NAME = "fc.telemetry:type=Heartbeat";

    /** 心跳通知类型。 */
    public static final String TYPE = "fc.heartbeat";

    private final MBeanServer server;
    private final ObjectName name;
    private final Timer timer;
    private final Integer id;
    private final int seconds;
    private final AtomicLong triggered;
    private volatile boolean closed;

    private Heartbeat(MBeanServer server, ObjectName name, Timer timer, Integer id,
                      int seconds, AtomicLong triggered) {
        this.server = server;
        this.name = name;
        this.timer = timer;
        this.id = id;
        this.seconds = seconds;
        this.triggered = triggered;
    }

    /**
     * 安装并启动心跳。
     *
     * @param seconds 周期秒数（1–3600）
     */
    public static Heartbeat start(int seconds) throws JMException {
        if (seconds < 1 || seconds > 3_600) {
            throw new IllegalArgumentException("心跳周期必须在 1–3600 秒之间：" + seconds);
        }
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName(OBJECT_NAME);
        Timer timer = new Timer();
        server.registerMBean(timer, name);
        try {
            Integer id = timer.addNotification(TYPE, "Frontier Chronicle 心跳",
                    Map.of("seconds", seconds), new Date(), seconds * 1_000L);
            // Timer 的 getNbOccurences 返回配置次数（0 表示无限），不是已发送次数；
            // 这里挂一个内部监听器自行计数，供运维状态查询使用。
            AtomicLong triggered = new AtomicLong();
            server.addNotificationListener(name, (notification, handback) -> {
                if (TYPE.equals(notification.getType())) {
                    triggered.incrementAndGet();
                }
            }, null, null);
            timer.start();
            return new Heartbeat(server, name, timer, id, seconds, triggered);
        } catch (RuntimeException e) {
            server.unregisterMBean(name);
            throw e;
        }
    }

    public ObjectName name() {
        return name;
    }

    public int seconds() {
        return seconds;
    }

    /** 已触发的心跳次数（由内部监听器统计）。 */
    public long beats() {
        return triggered.get();
    }

    public boolean isActive() {
        return !closed && timer.isActive();
    }

    /** 停止定时器、移除通知并注销 MBean；幂等。 */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            timer.stop();
        } catch (RuntimeException ignored) {
            // 未启动或已停止。
        }
        try {
            timer.removeNotification(id);
        } catch (InstanceNotFoundException ignored) {
            // 通知已不存在。
        }
        try {
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
        } catch (JMException ignored) {
            // 注销失败不影响退出。
        }
    }
}
