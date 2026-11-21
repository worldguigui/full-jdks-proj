package fc.auth;

import fc.testkit.FcTest;
import fc.testkit.TestRunner;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.sasl.AuthorizeCallback;
import javax.security.sasl.RealmCallback;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;
import java.util.Map;

/** SASL 机制发现/多轮交换/失败路径/Callback、释放与 Kerberos 降级探测。 */
public final class AuthTest {

    private static final UserDirectory USERS = UserDirectory.of(
            Map.of("captain", "frontier", "crew", "patrol"));

    public static void main(String[] args) {
        boolean passed = TestRunner.report(TestRunner.run(AuthTest.class));
        System.exit(passed ? 0 : 1);
    }

    @FcTest
    public void discoversFcChallengeOnBothSides() {
        SaslAuthenticator.Mechanisms mechanisms = SaslAuthenticator.mechanisms();
        assert mechanisms.client().contains(FcSaslProvider.MECHANISM)
                : mechanisms.client();
        assert mechanisms.server().contains(FcSaslProvider.MECHANISM)
                : mechanisms.server();
        assert mechanisms.supports(FcSaslProvider.MECHANISM);
        assert mechanisms.client().equals(mechanisms.client().stream().sorted().toList())
                : "机制列表必须排序";
    }

    @FcTest
    public void successfulMultiRoundExchangeProducesIdentity() throws Exception {
        AuthIdentity identity = SaslAuthenticator.authenticate("FC-CHALLENGE",
                "captain", "frontier".toCharArray(), null, "frontier", USERS);
        assert identity.authenticationId().equals("captain") : identity;
        assert identity.authorizationId().equals("captain") : identity;
        assert identity.mechanism().equals("FC-CHALLENGE") : identity;
        assert identity.authenticatedAt() != null;

        FcSaslClient client = new FcSaslClient(null, "frontier", "localhost",
                LocalCallbacks.client("captain", "frontier".toCharArray(), "frontier"));
        FcSaslServer server = new FcSaslServer("frontier", "localhost",
                LocalCallbacks.server(USERS, "frontier"));
        try {
            int rounds = 0;
            byte[] token = new byte[0];
            while (!server.isComplete() && rounds < 8) {
                byte[] challenge = server.evaluateResponse(token);
                token = client.evaluateChallenge(challenge);
                rounds++;
            }
            assert server.isComplete() && client.isComplete();
            assert rounds >= 2 : "FC-CHALLENGE 应为多轮交换：" + rounds;
            assert server.getAuthorizationID().equals("captain");
        } finally {
            client.dispose();
            server.dispose();
        }
    }

    @FcTest
    public void authenticationFailuresAreExplicit() {
        assertFails("wrong-password", () -> SaslAuthenticator.authenticate(
                "FC-CHALLENGE", "captain", "wrong".toCharArray(), null, "frontier", USERS),
                "凭据");
        assertFails("unknown-user", () -> SaslAuthenticator.authenticate(
                "FC-CHALLENGE", "nobody", "x".toCharArray(), null, "frontier", USERS),
                "未知用户");
        assertFails("unsupported-mechanism", () -> SaslAuthenticator.authenticate(
                "NO-SUCH-MECHANISM", "captain", "frontier".toCharArray(), null,
                "frontier", USERS), "不支持");
        assertFails("authorization-mismatch", () -> SaslAuthenticator.authenticate(
                "FC-CHALLENGE", "crew", "patrol".toCharArray(), "captain",
                "frontier", USERS), "授权");
    }

    @FcTest
    public void callbackHandlerProvidesAndAuthorizesIdentity() throws Exception {
        NameCallback name = new NameCallback("用户名");
        PasswordCallback password = new PasswordCallback("口令", false);
        RealmCallback realm = new RealmCallback("realm");
        LocalCallbacks.client("captain", "frontier".toCharArray(), "bridge")
                .handle(new Callback[] {name, password, realm});
        assert name.getName().equals("captain");
        assert new String(password.getPassword()).equals("frontier");
        assert realm.getText().equals("bridge");
        password.clearPassword();

        LocalCallbacks server = LocalCallbacks.server(USERS, "bridge");
        NameCallback serverName = new NameCallback("用户名", "crew");
        PasswordCallback serverPassword = new PasswordCallback("口令", false);
        AuthorizeCallback same = new AuthorizeCallback("crew", "crew");
        server.handle(new Callback[] {serverName, serverPassword, same});
        assert new String(serverPassword.getPassword()).equals("patrol");
        assert same.isAuthorized();
        serverPassword.clearPassword();

        LocalCallbacks deny = LocalCallbacks.server(USERS, "bridge");
        AuthorizeCallback cross = new AuthorizeCallback("crew", "captain");
        deny.handle(new Callback[] {new NameCallback("用户名", "crew"),
                new PasswordCallback("口令", false), cross});
        assert !cross.isAuthorized() : "跨身份授权必须拒绝";
    }

    @FcTest
    public void directClientAndServerReleaseResources() throws Exception {
        FcSaslClient client = new FcSaslClient(null, "p", "s",
                LocalCallbacks.client("captain", "frontier".toCharArray(), "r"));
        client.dispose();
        assert client.isDisposed();
        assertFails("client-disposed", () -> client.evaluateChallenge(new byte[0]),
                "已释放");

        FcSaslServer server = new FcSaslServer("p", "s",
                LocalCallbacks.server(USERS, "r"));
        server.dispose();
        assert server.isDisposed();
        assertFails("server-disposed", () -> server.evaluateResponse(new byte[0]),
                "已释放");
    }

    @FcTest
    public void kerberosProbeNeverFakesReadiness() {
        KerberosEnvironment.GssProbe probe = KerberosEnvironment.of(
                "frontier/localhost@FRONTIER", "", "", "").probe();
        assert !probe.readyForLogin() : "没有 realm/KDC 时不得报告可登录";
        assert probe.mechanisms() != null;
        assert probe.summary().contains("未配置") : probe.summary();
        String described = GssAuthenticator.describe(new org.ietf.jgss.GSSException(
                org.ietf.jgss.GSSException.FAILURE, 0, "KDC unreachable"));
        assert described.contains("KDC") : described;

        KerberosEnvironment env = KerberosEnvironment.of("frontier/localhost@FRONTIER",
                "FRONTIER", "127.0.0.1", "");
        assert env.probe().realmConfigured();
        assert env.probe().kdcConfigured();
    }

    private interface Throwing {
        Object run() throws Exception;
    }

    private static void assertFails(String label, Throwing action, String expected) {
        boolean failed = false;
        try {
            action.run();
        } catch (Exception e) {
            failed = true;
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            assert message.contains(expected) : label + " 错误信息不含「" + expected + "」：" + message;
        }
        assert failed : label + " 必须失败";
    }
}
