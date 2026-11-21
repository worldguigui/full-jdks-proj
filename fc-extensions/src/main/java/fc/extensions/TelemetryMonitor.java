package fc.extensions;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.monitor.GaugeMonitor;
import java.lang.management.ManagementFactory;

/**
 * 用 {@link GaugeMonitor} 监视游戏遥测的数值属性，越过高/低阈值时由 JMX
 * 监视器自动广播 {@code jmx.monitor.gauge.high}/{@code ...low} 通知。
 *
 * <p>注册名 {@code fc.telemetry:type=Gauge}。监视器本身是一个标准 MBean，
 * 观测对象指向 {@link GameTelemetry#OBJECT_NAME}；调用方只需订阅通知，
 * 不必轮询。{@link #close()} 停止监视并注销。</p>
 */
public final class TelemetryMonitor implements AutoCloseable {

    public static final String OBJECT_NAME = "fc.telemetry:type=Gauge";

    /** 监视器的采样间隔（毫秒）。 */
    public static final long GRANULARITY_MILLIS = 100;

    private final MBeanServer server;
    private final ObjectName name;
    private final GaugeMonitor monitor;
    private volatile boolean closed;

    private TelemetryMonitor(MBeanServer server, ObjectName name, GaugeMonitor monitor) {
        this.server = server;
        this.name = name;
        this.monitor = monitor;
    }

    /**
     * 在平台 MBeanServer 上安装并启动监视器。
     *
     * @param attribute 被观测的遥测属性（如 {@code Ships}、{@code CommandCount}）
     * @param high      高阈值；高于它时通知
     * @param low       低阈值；低于它时通知（须小于 high）
     */
    public static TelemetryMonitor start(String attribute, int high, int low)
            throws JMException {
        if (attribute == null || attribute.isBlank()) {
            throw new IllegalArgumentException("观测属性不能为空");
        }
        if (!(low < high)) {
            throw new IllegalArgumentException("低阈值必须小于高阈值：" + low + " / " + high);
        }
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName(OBJECT_NAME);
        GaugeMonitor monitor = new GaugeMonitor();
        server.registerMBean(monitor, name);
        try {
            monitor.setObservedObject(new ObjectName(GameTelemetry.OBJECT_NAME));
            monitor.setObservedAttribute(attribute);
            monitor.setGranularityPeriod(GRANULARITY_MILLIS);
            monitor.setThresholds(high, low);
            monitor.setNotifyHigh(true);
            monitor.setNotifyLow(true);
            monitor.setDifferenceMode(false);
            monitor.start();
            return new TelemetryMonitor(server, name, monitor);
        } catch (JMException | RuntimeException e) {
            server.unregisterMBean(name);
            throw e;
        }
    }

    public ObjectName name() {
        return name;
    }

    public String observedAttribute() {
        return monitor.getObservedAttribute();
    }

    public int highThreshold() {
        return monitor.getHighThreshold().intValue();
    }

    public int lowThreshold() {
        return monitor.getLowThreshold().intValue();
    }

    /** 停止监视并注销 MBean；幂等。 */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            monitor.stop();
        } catch (RuntimeException ignored) {
            // 未启动或已停止。
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
