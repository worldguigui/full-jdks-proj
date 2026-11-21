package fc.extensions;

import fc.testkit.FcTest;
import fc.testkit.TestRunner;

import javax.management.Attribute;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import java.io.IOException;
import java.util.Map;

/** 回环 JMX：启动/连接/遥测读取、多客户端、停止、端口校验与缺失 MBean。 */
public final class RemoteJmxTest {

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(RemoteJmxTest.class))) {
            System.exit(1);
        }
    }

    @FcTest
    public void startConnectAndReadTelemetry() throws Exception {
        GameTelemetry telemetry = GameTelemetry.register();
        telemetry.update(7, "星历 7", "abc123", 16, 3, "1234", "", 9);
        try (RemoteJmx.Server server = RemoteJmx.start();
             JMXConnector connector = RemoteJmx.connect(server.url())) {
            assert server.isRunning();
            assert server.port() > 0 : server.port();
            assert server.url().getURLPath().contains("127.0.0.1") : server.url();
            assert server.url().getURLPath().endsWith("/" + RemoteJmx.CONNECTOR_NAME)
                    : server.url();

            Map<String, Object> snapshot =
                    RemoteJmx.snapshot(connector.getMBeanServerConnection());
            assert ((Number) snapshot.get("Day")).longValue() == 7 : snapshot;
            assert snapshot.get("StateHash").equals("abc123") : snapshot;
            assert ((Number) snapshot.get("Ships")).intValue() == 3 : snapshot;
            assert ((Number) snapshot.get("CommandCount")).intValue() == 9 : snapshot;
            assert snapshot.get("Credits").equals("1234") : snapshot;
        } finally {
            telemetry.close();
        }
    }

    @FcTest
    public void multipleClientsShareTheServer() throws Exception {
        GameTelemetry telemetry = GameTelemetry.register();
        telemetry.update(2, "星历 2", "h", 16, 3, "500", "", 1);
        try (RemoteJmx.Server server = RemoteJmx.start();
             JMXConnector first = RemoteJmx.connect(server.url());
             JMXConnector second = RemoteJmx.connect(server.url())) {
            assert RemoteJmx.snapshot(first.getMBeanServerConnection())
                    .get("Day").equals(2L);
            assert RemoteJmx.snapshot(second.getMBeanServerConnection())
                    .get("Day").equals(2L);
        } finally {
            telemetry.close();
        }
    }

    @FcTest
    public void readOnlyAttributesRejectWrites() throws Exception {
        GameTelemetry telemetry = GameTelemetry.register();
        try (RemoteJmx.Server server = RemoteJmx.start();
             JMXConnector connector = RemoteJmx.connect(server.url())) {
            var connection = connector.getMBeanServerConnection();
            var applied = RemoteJmx.setAttributes(connection,
                    new ObjectName(GameTelemetry.OBJECT_NAME),
                    new Attribute("Day", 99L));
            assert applied.isEmpty() : "只读 MBean 必须拒绝属性写入：" + applied;
            assert ((Number) RemoteJmx.snapshot(connection).get("Day")).longValue() == 0;
        } finally {
            telemetry.close();
        }
    }

    @FcTest
    public void stopClosesTheEndpoint() throws Exception {
        RemoteJmx.Server server = RemoteJmx.start();
        javax.management.remote.JMXServiceURL url = server.url();
        assert server.isRunning();
        server.close();
        assert !server.isRunning();
        server.close();

        boolean refused = false;
        for (int i = 0; i < 10 && !refused; i++) {
            try (JMXConnector ignored = RemoteJmx.connect(url)) {
                Thread.sleep(100);
            } catch (IOException e) {
                refused = true;
            }
        }
        assert refused : "停止后连接必须被拒绝";
    }

    @FcTest
    public void invalidPortsAndMissingTelemetryAreRejected() throws Exception {
        boolean negative = false;
        try {
            RemoteJmx.start(-1);
        } catch (IllegalArgumentException e) {
            negative = true;
        }
        assert negative;
        boolean tooLarge = false;
        try {
            RemoteJmx.start(70_000);
        } catch (IllegalArgumentException e) {
            tooLarge = true;
        }
        assert tooLarge;

        try (RemoteJmx.Server server = RemoteJmx.start()) {
            boolean doubleBind = false;
            try {
                RemoteJmx.start(server.port());
            } catch (IOException e) {
                doubleBind = true;
                assert e.getMessage().contains("注册表") : e.getMessage();
            }
            assert doubleBind : "同一端口重复启动必须失败";

            try (JMXConnector connector = RemoteJmx.connect(server.url())) {
                boolean missing = false;
                try {
                    RemoteJmx.snapshot(connector.getMBeanServerConnection());
                } catch (IOException e) {
                    missing = true;
                    assert e.getMessage().contains("遥测") : e.getMessage();
                }
                assert missing : "未注册遥测 MBean 时应明确失败";
            }
        }
    }
}
