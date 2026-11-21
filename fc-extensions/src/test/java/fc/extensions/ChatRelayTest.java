package fc.extensions;

import fc.testkit.FcTest;
import fc.testkit.TestRunner;

import java.io.IOException;
import java.rmi.Naming;

/** 聊天室中继：TCP 注册/广播、成员表、历史、RMI 控制面、错误路径与关闭语义。 */
public final class ChatRelayTest {

    public static void main(String[] args) {
        boolean passed = TestRunner.report(TestRunner.run(ChatRelayTest.class));
        // 控制面测试会在本进程内做 RMI 查找；JDK 的 RMI Reaper 是非守护线程，
        // 中继关闭后仍会留存，因此测试完成后显式结束进程。
        System.exit(passed ? 0 : 1);
    }

    private static String await(ChatRelay.Client client, String prefix) throws IOException {
        String line;
        while ((line = client.readLine()) != null) {
            if (line.startsWith(prefix)) {
                return line;
            }
        }
        return null;
    }

    private static String expect(ChatRelay.Client client, String prefix) throws IOException {
        String line = await(client, prefix);
        assert line != null : "未收到以 " + prefix + " 开头的行";
        return line;
    }

    @FcTest
    public void joinAndMessagesReachEveryMember() throws Exception {
        try (ChatRelay relay = ChatRelay.start();
             ChatRelay.Client captain = relay.connect();
             ChatRelay.Client trader = relay.connect()) {
            assert expect(captain, "WELCOME").contains("NICK");
            assert expect(trader, "WELCOME").contains("NICK");

            captain.send("NICK 舰长");
            assert expect(captain, "OK 1 舰长").equals("OK 1 舰长");
            expect(captain, "JOIN 1 舰长");

            trader.send("NICK 商人");
            expect(trader, "OK 2 商人");
            expect(trader, "JOIN 2 商人");
            assert expect(captain, "JOIN 2 商人").endsWith("在线 2");

            captain.send("MSG 已抵达边境");
            assert expect(captain, "MSG ").contains("舰长 已抵达边境");
            assert expect(trader, "MSG ").contains("舰长 已抵达边境");

            assert relay.memberCount() == 2 : relay.memberCount();
            assert relay.messageCount() == 1 : relay.messageCount();
            assert relay.history(10).size() == 1;
            assert relay.history(10).get(0).nick().equals("舰长");
            assert relay.endpoint().equals("fc://chat/loopback:" + relay.port());
        }
    }

    @FcTest
    public void rosterHistoryAndSystemBroadcastAreAvailable() throws Exception {
        try (ChatRelay relay = ChatRelay.start();
             ChatRelay.Client client = relay.connect()) {
            client.send("NICK 观测员");
            expect(client, "OK");

            client.send("MSG 第一批补给已装船");
            expect(client, "MSG ");
            client.send("MSG 航线已确认");
            expect(client, "MSG ");

            client.send("WHO");
            assert expect(client, "ROSTER 1").equals("ROSTER 1");
            assert expect(client, "USER ").contains("观测员");
            expect(client, "END");

            client.send("HIST 1");
            assert expect(client, "HISTORY 1").equals("HISTORY 1");
            assert expect(client, "MSG ").contains("航线已确认");
            expect(client, "END");

            assert relay.broadcast("中继维护开始") == 1;
            assert expect(client, "MSG ").contains("中继 中继维护开始");
            assert relay.messageCount() == 3 : relay.messageCount();
            assert relay.history(2).size() == 2;
            assert relay.history(0).isEmpty();
            assert relay.history(-1).isEmpty();
        }
    }

    @FcTest
    public void rmiControlExposesRosterAndBroadcast() throws Exception {
        try (ChatRelay relay = ChatRelay.start();
             ChatRelay.Client client = relay.connect()) {
            client.send("NICK 舰长");
            expect(client, "OK");

            assert relay.controlEndpoint().startsWith("rmi://127.0.0.1:");
            ChatRelay.Control control =
                    (ChatRelay.Control) Naming.lookup(relay.controlEndpoint());
            String[] roster = control.roster();
            assert roster.length == 1 : roster.length;
            assert roster[0].contains("舰长") : roster[0];
            assert control.messageCount() == 0;

            assert control.broadcast("控制面公告") == 1;
            assert expect(client, "MSG ").contains("中继 控制面公告");
            assert control.messageCount() == 1;
        }
    }

    @FcTest
    public void invalidInputsAreRejectedWithoutClosingTheSession() throws Exception {
        try (ChatRelay relay = ChatRelay.start();
             ChatRelay.Client client = relay.connect();
             ChatRelay.Client duplicate = relay.connect()) {
            expect(client, "WELCOME");
            expect(duplicate, "WELCOME");

            client.send("NICK");
            assert expect(client, "ERROR").contains("昵称不能为空");
            client.send("NICK " + "x".repeat(ChatRelay.MAX_NICK + 1));
            assert expect(client, "ERROR").contains("昵称过长");
            client.send("MSG 还没注册");
            assert expect(client, "ERROR").contains("请先发送 NICK");

            client.send("NICK 舰长");
            expect(client, "OK");
            duplicate.send("NICK 舰长");
            assert expect(duplicate, "ERROR").contains("昵称已被占用");

            client.send("MSG");
            assert expect(client, "ERROR").contains("消息不能为空");
            client.send("MSG " + "y".repeat(ChatRelay.MAX_MESSAGE + 1));
            assert expect(client, "ERROR").contains("消息过长");
            client.send("NICK 第二身份");
            assert expect(client, "ERROR").contains("已注册");
            client.send("FOO");
            assert expect(client, "ERROR").contains("未知命令");
            client.send("PING");
            assert expect(client, "PONG 0").equals("PONG 0");

            client.send("MSG 仍然可用");
            assert expect(client, "MSG ").contains("仍然可用");
            assert relay.memberCount() == 1 : relay.memberCount();
        }
    }

    @FcTest
    public void disconnectBroadcastsPart() throws Exception {
        try (ChatRelay relay = ChatRelay.start();
             ChatRelay.Client captain = relay.connect()) {
            captain.send("NICK 舰长");
            expect(captain, "OK");
            ChatRelay.Client trader = relay.connect();
            trader.send("NICK 商人");
            expect(trader, "OK");
            expect(captain, "JOIN 2 商人");
            expect(trader, "JOIN 2 商人");

            trader.close();
            assert expect(captain, "PART 2 商人").endsWith("在线 1");
            assert relay.memberCount() == 1;
        }
    }

    @FcTest
    public void stopClosesEndpointAndRejectsNewConnections() throws Exception {
        ChatRelay relay = ChatRelay.start();
        assert relay.isRunning();
        relay.close();
        relay.close();
        assert !relay.isRunning();

        boolean refused = false;
        try {
            relay.connect().close();
        } catch (ChatRelay.ChatException e) {
            refused = true;
        }
        assert refused : "关闭后连接必须被拒绝";

        boolean zeroHistory = false;
        try {
            ChatRelay.start(0, 0);
        } catch (IllegalArgumentException e) {
            zeroHistory = true;
        }
        assert zeroHistory;
        boolean negativePort = false;
        try {
            ChatRelay.start(-1);
        } catch (IllegalArgumentException e) {
            negativePort = true;
        }
        assert negativePort;
    }
}
