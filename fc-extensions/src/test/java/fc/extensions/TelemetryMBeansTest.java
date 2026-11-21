package fc.extensions;

import fc.testkit.FcTest;
import fc.testkit.TestRunner;

import javax.management.Attribute;
import javax.management.AttributeChangeNotification;
import javax.management.AttributeNotFoundException;
import javax.management.MBeanServer;
import javax.management.Notification;
import javax.management.NotificationFilterSupport;
import javax.management.ObjectName;
import javax.management.monitor.MonitorNotification;
import javax.management.openmbean.CompositeData;
import javax.management.timer.TimerNotification;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** JMX 通知、动态 MBean、GaugeMonitor、Timer 心跳与 QueryExp 目录查询。 */
public final class TelemetryMBeansTest {

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(TelemetryMBeansTest.class))) {
            System.exit(1);
        }
    }

    private static MBeanServer server() {
        return ManagementFactory.getPlatformMBeanServer();
    }

    @FcTest
    public void telemetryBroadcastsAttributeAndCommandNotifications() throws Exception {
        MBeanServer server = server();
        GameTelemetry telemetry = GameTelemetry.register();
        List<Notification> received = Collections.synchronizedList(new ArrayList<>());
        List<Notification> commands = Collections.synchronizedList(new ArrayList<>());
        try {
            ObjectName name = new ObjectName(GameTelemetry.OBJECT_NAME);
            server.addNotificationListener(name, (notification, handback) ->
                    received.add(notification), null, "handback");
            NotificationFilterSupport onlyCommands = new NotificationFilterSupport();
            onlyCommands.enableType(GameTelemetry.COMMAND_NOTIFICATION);
            server.addNotificationListener(name, (notification, handback) ->
                    commands.add(notification), onlyCommands, null);

            telemetry.update(7, "7-01-01", "abc", 16, 3, "1000", "", 0);
            telemetry.setCommandCount(3);
            telemetry.setCommandCount(3);
            telemetry.update(8, "7-01-02", "def", 16, 3, "1000", "", 3);

            assert telemetry.notificationSequence() == 3
                    : "应只广播 3 次：" + telemetry.notificationSequence();
            assert received.size() == 3 : received;
            long attributeChanges = received.stream()
                    .filter(AttributeChangeNotification.class::isInstance).count();
            assert attributeChanges == 2 : "两次状态哈希变化：" + attributeChanges;
            AttributeChangeNotification last = (AttributeChangeNotification) received.stream()
                    .filter(AttributeChangeNotification.class::isInstance)
                    .reduce((a, b) -> b).orElseThrow();
            assert "StateHash".equals(last.getAttributeName()) : last;
            assert "abc".equals(last.getOldValue()) : last.getOldValue();
            assert "def".equals(last.getNewValue()) : last.getNewValue();

            assert commands.size() == 1 : "重复计数不广播：" + commands;
            Notification command = commands.get(0);
            assert GameTelemetry.COMMAND_NOTIFICATION.equals(command.getType()) : command;
            assert command.getUserData() instanceof Map<?, ?> data : command.getUserData();
            Map<?, ?> data = (Map<?, ?>) command.getUserData();
            assert ((Number) data.get("commandCount")).intValue() == 3 : data;
            assert ((Number) data.get("day")).longValue() == 7 : data;
            assert telemetry.getNotificationInfo().length == 2;
        } finally {
            telemetry.close();
        }
        assert !server.isRegistered(new ObjectName(GameTelemetry.OBJECT_NAME));
    }

    @FcTest
    public void catalogDynamicMBeanExposesSnapshotAndFind() throws Exception {
        GameTelemetry telemetry = GameTelemetry.register();
        FrontierCatalog catalog = FrontierCatalog.register();
        try {
            MBeanServer server = server();
            ObjectName name = new ObjectName(FrontierCatalog.OBJECT_NAME);
            telemetry.update(7, "星历 7", "abc123", 16, 4, "1450", "", 12);

            assert ((Integer) server.getAttribute(name, "BeanCount")) >= 2;
            assert ((Long) server.getAttribute(name, "GameDay")) == 7L;
            assert "abc123".equals(server.getAttribute(name, "GameHash"));

            CompositeData snapshot = (CompositeData) server.invoke(name, "snapshot",
                    new Object[0], new String[0]);
            assert ((Long) snapshot.get("day")) == 7L : snapshot;
            assert "abc123".equals(snapshot.get("stateHash")) : snapshot;
            assert ((Integer) snapshot.get("ships")) == 4 : snapshot;
            assert "1450".equals(snapshot.get("credits")) : snapshot;

            String[] found = (String[]) server.invoke(name, "find",
                    new Object[] {"fc.telemetry"}, new String[] {"java.lang.String"});
            assert found.length >= 2 : List.of(found);
            assert List.of(found).contains(GameTelemetry.OBJECT_NAME) : List.of(found);

            assert server.getAttributes(name,
                    new String[] {"BeanCount", "GameHash", "Missing"}).size() == 2;
            boolean rejected = false;
            try {
                server.setAttribute(name, new Attribute("GameDay", 99L));
            } catch (AttributeNotFoundException e) {
                rejected = true;
            }
            assert rejected : "目录属性必须只读";
            assert server.getMBeanInfo(name).getAttributes().length == 3;
            assert server.getMBeanInfo(name).getOperations().length == 2;
        } finally {
            catalog.close();
            telemetry.close();
        }
        assert !server().isRegistered(new ObjectName(FrontierCatalog.OBJECT_NAME));
    }

    @FcTest
    public void gaugeMonitorFiresWhenShipsCrossHighThreshold() throws Exception {
        GameTelemetry telemetry = GameTelemetry.register();
        List<Notification> received = Collections.synchronizedList(new ArrayList<>());
        try (TelemetryMonitor monitor = TelemetryMonitor.start("Ships", 8, 2)) {
            MBeanServer server = server();
            telemetry.update(1, "1-01-01", "h1", 16, 3, "0", "", 0);
            assert server.isRegistered(monitor.name());
            assert "Ships".equals(monitor.observedAttribute());
            assert monitor.highThreshold() == 8 : monitor.highThreshold();
            assert monitor.lowThreshold() == 2 : monitor.lowThreshold();
            assert Boolean.TRUE.equals(server.getAttribute(monitor.name(), "NotifyHigh"));
            assert Boolean.TRUE.equals(server.getAttribute(monitor.name(), "NotifyLow"));

            server.addNotificationListener(monitor.name(),
                    (notification, handback) -> received.add(notification), null, null);
            telemetry.update(2, "1-01-02", "h2", 16, 12, "0", "", 0);

            MonitorNotification high = null;
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline && high == null) {
                Thread.sleep(100);
                synchronized (received) {
                    high = received.stream()
                            .filter(MonitorNotification.class::isInstance)
                            .map(MonitorNotification.class::cast)
                            .filter(n -> n.getType().endsWith(".high"))
                            .findFirst().orElse(null);
                }
            }
            assert high != null : "越过阈值应发出 high 通知：" + received;
            assert "Ships".equals(high.getObservedAttribute()) : high;
            assert ((Number) high.getDerivedGauge()).doubleValue() >= 12.0
                    : high.getDerivedGauge();
        } finally {
            telemetry.close();
        }
        assert !server().isRegistered(new ObjectName(TelemetryMonitor.OBJECT_NAME));
    }

    @FcTest
    public void timerHeartbeatPublishesPeriodicNotifications() throws Exception {
        MBeanServer server = server();
        List<Notification> received = Collections.synchronizedList(new ArrayList<>());
        try (Heartbeat heartbeat = Heartbeat.start(1)) {
            assert heartbeat.isActive();
            assert heartbeat.seconds() == 1;
            assert server.isRegistered(new ObjectName(Heartbeat.OBJECT_NAME));
            NotificationFilterSupport onlyBeats = new NotificationFilterSupport();
            onlyBeats.enableType(Heartbeat.TYPE);
            server.addNotificationListener(heartbeat.name(),
                    (notification, handback) -> received.add(notification), onlyBeats, null);

            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline && received.isEmpty()) {
                Thread.sleep(100);
            }
            assert !received.isEmpty() : "定时器应周期发送心跳";
            Notification first = received.get(0);
            assert first instanceof TimerNotification : first.getClass();
            assert Heartbeat.TYPE.equals(first.getType()) : first;
            assert heartbeat.beats() >= 1 : heartbeat.beats();
        }
        assert !server.isRegistered(new ObjectName(Heartbeat.OBJECT_NAME));
    }

    @FcTest
    public void queryExpFiltersPublishedTelemetry() throws Exception {
        GameTelemetry telemetry = GameTelemetry.register();
        FrontierCatalog catalog = FrontierCatalog.register();
        try {
            MBeanServer server = server();
            telemetry.update(3, "3-01-01", "abc", 16, 3, "0", "", 0);
            ObjectName game = new ObjectName(GameTelemetry.OBJECT_NAME);
            ObjectName cat = new ObjectName(FrontierCatalog.OBJECT_NAME);

            Set<ObjectName> all = RemoteJmx.allBeans(server, "fc.*");
            assert all.contains(game) && all.contains(cat) : all;
            Set<ObjectName> published = RemoteJmx.publishedBeans(server);
            assert published.contains(game) : published;
            assert !published.contains(cat) : "目录没有 StateHash，不应命中";

            telemetry.update(4, "4-01-01", "", 16, 3, "0", "", 0);
            assert RemoteJmx.publishedBeans(server).isEmpty()
                    : "哈希为空时不应再命中已发布集合";
        } finally {
            catalog.close();
            telemetry.close();
        }
        boolean rejected = false;
        try {
            RemoteJmx.allBeans(server(), "bad:domain");
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        assert rejected;
    }
}
