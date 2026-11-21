package fc.runtime;

import fc.config.ConfigException;
import fc.sim.StateHasher;
import fc.testkit.FcTest;
import fc.testkit.TestRunner;
import fc.world.ResourceKind;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

/**
 * 运行时集成测试：默认世界装配、CLI 主命令、同种子重放与守恒。
 */
public final class RuntimeSmokeTest {

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(RuntimeSmokeTest.class))) {
            System.exit(1);
        }
    }

    private static RuntimeSession boot() {
        return RuntimeSession.create(new String[0]);
    }

    @FcTest
    public void defaultWorldBootsFromConfiguration() {
        RuntimeSession session = boot();
        WorldState st = session.state();
        assert st.graph().placeCount() == 16 : "静态地点数 " + st.graph().placeCount();
        assert st.graph().ships().size() == 3 : "舰船数 " + st.graph().ships().size();
        assert st.agents().size() == 36 : "主体数 " + st.agents().size();
        assert st.factions().size() == 4 : "势力数 " + st.factions().size();
        assert st.colonies().size() == 3 : "殖民地数（含玩家前哨）" + st.colonies().size();
        assert st.credits().compareTo(new BigDecimal("1000")) == 0;
        assert session.banner().size() == 7;
        st.verifyLedger();
    }

    @FcTest
    public void coreCommandsRespond() {
        RuntimeSession session = boot();
        assert !session.cli().execute("status").isBlank();
        assert !session.cli().execute("map").isBlank();
        assert !session.cli().execute("help").isBlank();
        assert !session.cli().execute("goals").isBlank();
        assert !session.cli().execute("finance").isBlank();
        assert !session.cli().execute("factions").isBlank();
        assert !session.cli().execute("ships").isBlank();
        assert !session.cli().execute("market").isBlank();
        assert session.cli().execute("no-such-command").contains("未知");
    }

    @FcTest
    public void hashCommandMatchesStateHasher() {
        RuntimeSession session = boot();
        String out = session.cli().execute("hash");
        assert out.contains(StateHasher.hash(session.state())) : out;
    }

    @FcTest
    public void nextAdvancesTimeAndKeepsLedgersConsistent() {
        RuntimeSession session = boot();
        WorldState st = session.state();
        BigDecimal food = st.ledger().total(ResourceKind.FOOD);
        BigDecimal money = st.money().total();

        session.cli().execute("next 5");

        assert st.now().day() >= 1 : "next 应推进时间";
        st.verifyLedger();
        assert st.ledger().total(ResourceKind.FOOD).signum() >= 0;
        assert food.signum() >= 0 : "食物账本快照非负";
        assert money.compareTo(BigDecimal.ZERO) >= 0;
    }

    @FcTest
    public void sameSeedAndCommandsProduceSameHash() {
        RuntimeSession a = boot();
        RuntimeSession b = boot();
        a.cli().execute("next 20");
        b.cli().execute("next 20");
        assert a.state().now().day() == b.state().now().day();
        assert StateHasher.hash(a.state()).equals(StateHasher.hash(b.state()))
                : "同种子同命令应得到相同状态哈希";
    }

    @FcTest
    public void chronicleQueryAndExportStayOutOfReplayLog() throws Exception {
        RuntimeSession session = boot();
        session.execute("next 10");

        String table = session.execute(
                "query SELECT type, day FROM chronicle ORDER BY day DESC LIMIT 3");
        assert table.contains("type") && table.contains("day") : table;
        assert table.contains("共 3 行") : table;

        Path dir = Files.createTempDirectory("fc-runtime-export-");
        try {
            assert session.execute("export csv " + dir.resolve("c.csv")).contains("已导出");
            assert Files.exists(dir.resolve("c.csv"));
            assert session.execute("export xml " + dir.resolve("c.xml")).contains("已导出");
            assert Files.exists(dir.resolve("c.xml"));
            assert session.execute("export html " + dir.resolve("c.html")).contains("已导出");
            assert Files.exists(dir.resolve("c.html"));
            assert session.execute("export zip " + dir.resolve("c.zip")).contains("已导出");
            assert Files.exists(dir.resolve("c.zip"));
            String dual = session.execute("export csv " + dir.resolve("a.csv")
                    + " " + dir.resolve("b.csv"));
            assert dual.contains("已双份导出") || dual.contains("两阶段提交") : dual;
            assert Files.exists(dir.resolve("a.csv")) && Files.exists(dir.resolve("b.csv"));
            assert Arrays.equals(Files.readAllBytes(dir.resolve("a.csv")),
                    Files.readAllBytes(dir.resolve("b.csv"))) : "双份内容必须一致";
            try (var listing = Files.list(dir)) {
                assert listing.noneMatch(p -> p.getFileName().toString().startsWith(".fcxa-"))
                        : "事务完成后不得残留 XA 恢复日志";
            }
        } finally {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }

        assert session.commandHistory().contains("next 10") : session.commandHistory();
        assert session.commandHistory().stream()
                .noneMatch(c -> c.startsWith("query") || c.startsWith("export"))
                : "查询与导出不应进入重放日志";
    }

    @FcTest
    public void atlasCommandWritesImageAndStaysOutOfReplayLog() throws Exception {
        RuntimeSession session = boot();
        Path dir = Files.createTempDirectory("fc-runtime-atlas-");
        try {
            String out = session.execute("atlas png " + dir.resolve("atlas.png"));
            assert out.contains("已生成") : out;
            Path png = dir.resolve("atlas.png");
            assert Files.exists(png);
            assert Files.size(png) > 5000 : Files.size(png);
        } finally {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        assert session.commandHistory().stream().noneMatch(c -> c.startsWith("atlas"))
                : "制图不应进入重放日志";
    }

    @FcTest
    public void musicCommandWritesMidiAndStaysOutOfReplayLog() throws Exception {
        RuntimeSession session = boot();
        Path dir = Files.createTempDirectory("fc-runtime-music-");
        try {
            String out = session.execute("music mid " + dir.resolve("music.mid"));
            assert out.contains("配乐已生成") : out;
            assert out.contains("种子") : out;
            Path midi = dir.resolve("music.mid");
            assert Files.exists(midi);
            assert Files.size(midi) > 500 : Files.size(midi);
            assert session.execute("soundtrack wav " + dir.resolve("music.wav"))
                    .contains("配乐已生成");
            assert Files.size(dir.resolve("music.wav")) > 100_000;
        } finally {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        assert session.commandHistory().stream()
                .noneMatch(c -> c.startsWith("music") || c.startsWith("soundtrack"))
                : "配乐命令不应进入重放日志";
    }

    @FcTest
    public void vaultMirrorsChronicleAndStaysOutOfReplayLog() throws Exception {
        RuntimeSession session = boot();
        session.execute("next 5");
        Path dir = Files.createTempDirectory("fc-runtime-vault-");
        try {
            String written = session.execute("vault write " + dir);
            assert written.contains("档案快照已写入") : written;
            assert written.contains("sha256") : written;
            try (var listing = Files.list(dir)) {
                assert listing.anyMatch(p -> p.getFileName().toString().endsWith(".zip"))
                        : "应写出 ZIP 快照";
            }
            assert session.execute("vault list " + dir).contains("份") : "索引应可列出";
            assert session.execute("archive check " + dir).contains("通过") : "校验应通过";
        } finally {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        assert session.commandHistory().stream()
                .noneMatch(c -> c.startsWith("vault") || c.startsWith("archive"))
                : "档案库命令不应进入重放日志";
    }

    @FcTest
    public void jmxEndpointStartsReportsAndStops() {
        RuntimeSession session = boot();
        try {
            String started = session.execute("jmx 0");
            assert started.contains("JMX 端点已启动") : started;
            assert started.contains("service:jmx:rmi") : started;
            String status = session.execute("jmx status");
            assert status.contains("JMX 运行中") : status;
            String beans = session.execute("jmx beans");
            assert beans.contains("JMX 目录") : beans;
            assert beans.contains("fc 域 MBean 0 个") : beans;
            String heartbeat = session.execute("jmx heartbeat 60");
            assert heartbeat.contains("心跳已启动") : heartbeat;
            assert session.execute("jmx heartbeat status").contains("每 60 秒")
                    : "心跳状态应报告周期";
            assert session.execute("jmx heartbeat stop").contains("心跳已停止");
            assert session.execute("jmx stop").contains("已停止") : "应停止端点";
            assert session.execute("jmx status").contains("未启动") : "停止后应报告未启动";
        } finally {
            session.execute("jmx stop");
        }
        assert session.commandHistory().stream().noneMatch(c -> c.startsWith("jmx"))
                : "JMX 命令不应进入重放日志";
    }

    @FcTest
    public void chatRelayAcceptsMessagesAndStaysOutOfReplayLog() {
        RuntimeSession session = boot();
        try {
            String started = session.execute("chat open 0");
            assert started.contains("聊天室中继已启动") : started;
            assert started.contains("fc://chat/loopback:") : started;
            assert started.contains("rmi://127.0.0.1:") : started;

            String sent = session.execute("chat say 舰长 你好，边境");
            assert sent.contains("已发送") : sent;
            assert sent.contains("你好，边境") : sent;

            String history = session.execute("chat history 5");
            assert history.contains("聊天记录") : history;
            assert history.contains("舰长") && history.contains("你好，边境") : history;

            String roster = session.execute("chat roster");
            assert roster.contains("在线 0 人") : roster;
            assert roster.contains("消息 1 条") : roster;

            assert session.execute("chat close").contains("已停止") : "应停止中继";
            assert session.execute("chat roster").contains("未启动") : "停止后应报告未启动";
        } finally {
            session.execute("chat close");
        }
        assert session.commandHistory().stream().noneMatch(c -> c.startsWith("chat"))
                : "聊天室命令不应进入重放日志";
    }

    @FcTest
    public void namingCommandsResolveServicesAndStayOutOfReplayLog() {
        RuntimeSession session = boot();
        try {
            assert session.execute("res register fc:service/relay chat fc://chat/loopback:9999")
                    .contains("已注册") : "应注册命名条目";
            String located = session.execute("res locate fc:service/relay");
            assert located.contains("chat") && located.contains("fc://chat/loopback:9999")
                    : located;
            assert session.execute("res list service").contains("fc:service")
                    : "应枚举 service 命名空间";
            assert session.execute("res services").contains("chat →")
                    : "应列出服务引用";

            assert session.execute("chat open 0").contains("聊天室中继已启动");
            assert session.execute("res locate fc:service/chat").contains("chat")
                    : "聊天室启动时应自动登记";
            assert session.execute("chat close").contains("已停止");
            assert session.execute("res locate fc:service/chat").contains("未绑定")
                    : "聊天室停止后应解除登记";

            assert session.execute("res unregister fc:service/relay").contains("已解除");
        } finally {
            session.execute("chat close");
        }
        assert session.commandHistory().stream().noneMatch(c -> c.startsWith("res"))
                : "命名命令不应进入重放日志";
    }

    @FcTest
    public void trustStoreIssuesCertificatesAndStaysOutOfReplayLog() throws Exception {
        RuntimeSession session = boot();
        Path dir = Files.createTempDirectory("fc-runtime-trust-");
        try {
            String init = session.execute("trust init " + dir);
            assert init.contains("已生成信任库") : init;
            assert init.contains("SHA-256") : init;
            assert Files.exists(dir.resolve("ca.p12"));
            assert Files.exists(dir.resolve("server.p12"));
            assert Files.exists(dir.resolve("client.p12"));

            String status = session.execute("trust status " + dir);
            assert status.contains("校验通过") : status;
            assert status.contains("PKIX") : status;
            assert session.execute("trust init " + dir).contains("已存在");
            assert session.execute("trust status " + dir.resolve("absent"))
                    .contains("不存在");

            String started = session.execute("chat open 0 tls " + dir);
            assert started.contains("fc://chat/tls:") : started;
            assert started.contains("TLS 双向认证") : started;
            assert session.execute("chat say 舰长 加密信道").contains("加密信道")
                    : "TLS 中继应可收发消息";
            assert session.execute("chat close").contains("已停止");
        } finally {
            session.execute("chat close");
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        assert session.commandHistory().stream()
                .noneMatch(c -> c.startsWith("trust") || c.startsWith("chat"))
                : "信任库与聊天命令不应进入重放日志";
    }

    @FcTest
    public void scriptsExpandIntoReplayableCommands() throws Exception {
        RuntimeSession session = boot();
        String inline = session.execute("script eval set n 2; repeat 2 next 1; echo 巡逻 ${n}");
        assert inline.contains("脚本执行完成") : inline;
        assert session.commandHistory().stream().filter("next 1"::equals).count() == 2
                : session.commandHistory();
        assert session.commandHistory().stream().noneMatch(c -> c.startsWith("script"))
                : "脚本本身不应进入重放日志";

        Path dir = Files.createTempDirectory("fc-runtime-script-");
        try {
            Path file = dir.resolve("patrol.fcs");
            Files.writeString(file, "# 巡逻\nrepeat 3 next 1\necho 完成\n",
                    java.nio.charset.StandardCharsets.UTF_8);
            String ran = session.execute("script run " + file);
            assert ran.contains("已执行脚本") : ran;
            assert ran.contains("完成") : ran;
        } finally {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        assert session.commandHistory().stream().filter("next 1"::equals).count() == 5
                : session.commandHistory();

        String bad = session.execute("script eval repeat");
        assert bad.contains("脚本错误") && bad.contains("第 1 行") : bad;
    }

    @FcTest
    public void sealAndUnsealEvidenceRoundTrip() throws Exception {
        RuntimeSession session = boot();
        Path dir = Files.createTempDirectory("fc-runtime-seal-");
        String previousKeystore = System.getProperty("fc.seal.keystore");
        String previousPassword = System.getProperty("fc.seal.password");
        System.setProperty("fc.seal.keystore",
                dir.resolve("keys/evidence.p12").toString());
        System.setProperty("fc.seal.password", "runtime-seal-password");
        try {
            Path payload = dir.resolve("note.xml");
            Files.writeString(payload, "<note>封存内容</note>",
                    java.nio.charset.StandardCharsets.UTF_8);
            Path sealed = dir.resolve("note.xml.sealed");
            String sealedOut = session.execute("seal " + payload + " " + sealed);
            assert sealedOut.contains("已封存") : sealedOut;
            assert sealedOut.contains("AES-256-GCM") : sealedOut;
            assert Files.exists(sealed);
            assert !Arrays.equals(Files.readAllBytes(payload), Files.readAllBytes(sealed))
                    : "封存文件不得等于明文";

            Path opened = dir.resolve("note.opened.xml");
            String openedOut = session.execute("unseal " + sealed + " " + opened);
            assert openedOut.contains("已解封") : openedOut;
            assert Files.readString(opened, java.nio.charset.StandardCharsets.UTF_8)
                    .equals("<note>封存内容</note>");
        } finally {
            if (previousKeystore == null) {
                System.clearProperty("fc.seal.keystore");
            } else {
                System.setProperty("fc.seal.keystore", previousKeystore);
            }
            if (previousPassword == null) {
                System.clearProperty("fc.seal.password");
            } else {
                System.setProperty("fc.seal.password", previousPassword);
            }
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        assert session.commandHistory().stream()
                .noneMatch(c -> c.startsWith("seal") || c.startsWith("unseal"))
                : "封存命令不应进入重放日志";
    }

    @FcTest
    public void signAndVerifyEvidenceRoundTrip() throws Exception {
        RuntimeSession session = boot();
        Path dir = Files.createTempDirectory("fc-runtime-sign-");
        String previousKeys = System.getProperty("fc.keys.dir");
        System.setProperty("fc.keys.dir", dir.resolve("keys").toString());
        try {
            Path payload = dir.resolve("note.xml");
            Files.writeString(payload, "<note>边境纪事</note>", java.nio.charset.StandardCharsets.UTF_8);
            Path signed = dir.resolve("note.evidence.xml");
            String signedOut = session.execute("sign " + payload + " " + signed);
            assert signedOut.contains("已签名") : signedOut;
            assert Files.exists(signed);

            String verified = session.execute("verify " + signed);
            assert verified.contains("验签通过") : verified;
        } finally {
            if (previousKeys == null) {
                System.clearProperty("fc.keys.dir");
            } else {
                System.setProperty("fc.keys.dir", previousKeys);
            }
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        assert session.commandHistory().stream()
                .noneMatch(c -> c.startsWith("sign") || c.startsWith("verify"))
                : "签章命令不应进入重放日志";
    }

    @FcTest
    public void missingWorldArgumentIsRejected() {
        try {
            RuntimeSession.create(new String[] {"--world"});
            assert false : "缺少路径应被拒绝";
        } catch (ConfigException e) {
            assert e.getMessage().contains("--world") : e.getMessage();
        }
    }

    @FcTest
    public void missingWorldFileIsRejected() {
        try {
            RuntimeSession.create(new String[] {"--world", "no-such-world.xml"});
            assert false : "缺失文件应被拒绝";
        } catch (ConfigException e) {
            assert e.getMessage().contains("找不到世界配置文件") : e.getMessage();
        }
    }
}
