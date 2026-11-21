package fc.extensions;

import fc.testkit.FcTest;
import fc.testkit.TestRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Comparator;
import java.util.List;

import javax.net.ssl.SSLContext;

/** 聊天中继的 TLS 双向认证：证书链、握手、消息面与端口生命周期。 */
public final class TlsChatTest {

    private static final char[] PASSWORD = TrustAuthority.DEFAULT_PASSWORD.toCharArray();

    public static void main(String[] args) {
        boolean passed = TestRunner.report(TestRunner.run(TlsChatTest.class));
        // 中继会创建本机 RMI 注册表；为避免残留的非守护线程拖住测试 JVM，显式退出。
        System.exit(passed ? 0 : 1);
    }

    @FcTest
    public void handshakeRequiresClientCertificate() throws Exception {
        Setup setup = Setup.create();
        try (ChatRelay relay = setup.start()) {
            assert relay.secure();
            assert relay.endpoint().startsWith("fc://chat/tls:") : relay.endpoint();

            try (ChatRelay.Client client = relay.connectTls(setup.clientContext())) {
                assert client.readLine().startsWith("WELCOME");
                assert client.session() != null : "TLS 客户端应有会话";
                assert client.session().getProtocol().startsWith("TLS")
                        : client.session().getProtocol();
                assert client.session().getCipherSuite().startsWith("TLS_")
                        : client.session().getCipherSuite();

                X509Certificate peer =
                        (X509Certificate) client.session().getPeerCertificates()[0];
                assert TrustAuthority.fingerprint(peer)
                        .equals(TrustAuthority.fingerprint(setup.server().certificate()))
                        : "对端应是服务端证书";
                CertificateChains.verify(peer, setup.root().certificate());
            }

            assertThrows(IOException.class, () -> {
                try (ChatRelay.Client rejected = relay.connectTls(setup.anonymousContext())) {
                    rejected.readLine();
                }
            });
            assert relay.isRunning() : "拒绝匿名客户端后中继仍应可用";
        }
    }

    @FcTest
    public void messagesFlowOverTls() throws Exception {
        Setup setup = Setup.create();
        try (ChatRelay relay = setup.start();
             ChatRelay.Client captain = relay.connectTls(setup.clientContext());
             ChatRelay.Client navigator = relay.connectTls(setup.clientContext())) {
            captain.readLine();
            navigator.readLine();

            captain.send("NICK 舰长");
            assert captain.readLine().startsWith("OK") : "舰长应加入";
            assert captain.readLine().startsWith("JOIN") : "应广播舰长加入";

            navigator.send("NICK 领航员");
            assert navigator.readLine().startsWith("OK");
            assert next(navigator, "JOIN").contains("领航员");
            assert next(captain, "JOIN").contains("领航员") : "同伴应看到新成员";

            captain.send("MSG 加密信道畅通");
            String received = next(navigator, "MSG");
            assert received.contains("加密信道畅通") : received;
            assert received.contains("舰长") : received;

            navigator.send("WHO");
            List<String> roster = navigator.readBlock();
            assert roster.stream().anyMatch(line -> line.startsWith("USER"))
                    : roster;
            assert roster.get(roster.size() - 1).equals("END");

            captain.send("HIST 5");
            List<String> history = captain.readBlock();
            assert history.stream().anyMatch(line -> line.contains("加密信道畅通"))
                    : history;
        }
    }

    @FcTest
    public void keystoreFilesDriveServerAndClientContexts() throws Exception {
        Setup setup = Setup.create();
        Path dir = Files.createTempDirectory("fc-tls-");
        try {
            Path caFile = dir.resolve("ca.p12");
            Path serverFile = dir.resolve("server.p12");
            Path clientFile = dir.resolve("client.p12");
            SecureChannel.write(SecureChannel.trustStore(PASSWORD,
                    setup.root().certificate()), caFile, PASSWORD);
            SecureChannel.write(SecureChannel.keyStore(setup.server(),
                    TrustAuthority.chain(setup.server(), setup.root()), PASSWORD),
                    serverFile, PASSWORD);
            SecureChannel.write(SecureChannel.keyStore(setup.client(),
                    TrustAuthority.chain(setup.client(), setup.root()), PASSWORD),
                    clientFile, PASSWORD);

            SSLContext serverContext = SecureChannel.serverContext(serverFile, caFile, PASSWORD);
            SSLContext clientContext = SecureChannel.clientContext(clientFile, caFile, PASSWORD);
            try (ChatRelay relay = ChatRelay.start(0, 50, serverContext);
                 ChatRelay.Client client = relay.connectTls(clientContext)) {
                assert relay.secure();
                assert client.readLine().startsWith("WELCOME");
            }
        } finally {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    @FcTest
    public void plainClientCannotJoinTlsRelay() throws Exception {
        Setup setup = Setup.create();
        try (ChatRelay relay = setup.start()) {
            assertThrows(ChatRelay.ChatException.class, relay::connect);
            Throwable failure = capture(() -> relay.connect());
            assert failure.getMessage().contains("TLS") : failure.getMessage();
        }
    }

    @FcTest
    public void stoppedRelayRejectsTlsClients() throws Exception {
        Setup setup = Setup.create();
        ChatRelay relay = setup.start();
        relay.close();
        assert !relay.isRunning();
        assertThrows(ChatRelay.ChatException.class,
                () -> relay.connectTls(setup.clientContext()));

        assertThrows(IllegalArgumentException.class,
                () -> ChatRelay.start(-1, 10, setup.clientContext()));
    }

    /** 每个用例独立的一套 CA / 服务端 / 客户端材料与上下文。 */
    private record Setup(TrustAuthority.KeyMaterial root,
                         TrustAuthority.KeyMaterial server,
                         TrustAuthority.KeyMaterial client,
                         SSLContext serverContext,
                         SSLContext clientContext,
                         SSLContext anonymousContext) {

        static Setup create() throws Exception {
            TrustAuthority.KeyMaterial root = TrustAuthority.root("FC TLS Root");
            TrustAuthority.KeyMaterial server = TrustAuthority.server(root, "localhost");
            TrustAuthority.KeyMaterial client = TrustAuthority.client(root, "frontier-client");

            KeyStore serverKeys = SecureChannel.keyStore(server,
                    TrustAuthority.chain(server, root), PASSWORD);
            KeyStore clientKeys = SecureChannel.keyStore(client,
                    TrustAuthority.chain(client, root), PASSWORD);
            KeyStore trust = SecureChannel.trustStore(PASSWORD, root.certificate());

            return new Setup(root, server, client,
                    SecureChannel.context(serverKeys, PASSWORD, trust),
                    SecureChannel.context(clientKeys, PASSWORD, trust),
                    SecureChannel.context(null, PASSWORD, trust));
        }

        ChatRelay start() throws IOException {
            return ChatRelay.start(0, 50, serverContext);
        }
    }

    private static String next(ChatRelay.Client client, String prefix) throws IOException {
        for (String line; (line = client.readLine()) != null; ) {
            if (line.startsWith(prefix)) {
                return line;
            }
        }
        throw new AssertionError("没有读到以 " + prefix + " 开头的行");
    }

    private static Throwable capture(ThrowingCall call) {
        try {
            call.run();
        } catch (Throwable e) {
            return e;
        }
        throw new AssertionError("期望抛出异常，但没有异常");
    }

    private static void assertThrows(Class<? extends Throwable> type, ThrowingCall call)
            throws Exception {
        Throwable failure = capture(call);
        if (!type.isInstance(failure)) {
            throw new AssertionError("期望 " + type.getSimpleName() + "，实际 "
                    + failure.getClass().getSimpleName() + "：" + failure.getMessage(), failure);
        }
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
