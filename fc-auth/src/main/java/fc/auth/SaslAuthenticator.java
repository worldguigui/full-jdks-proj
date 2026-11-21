package fc.auth;

import javax.security.auth.callback.CallbackHandler;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslClientFactory;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;
import javax.security.sasl.SaslServerFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 可插拔 SASL 认证服务：机制通过 JCA Provider 注册，用
 * {@link Sasl#createSaslClient}/{@link Sasl#createSaslServer} 建立双方，
 * Token 由调用方负责传输；本类提供完整的多轮交换与身份结果。
 *
 * <p>FC-CHALLENGE 是自包含的挑战应答机制（HMAC-SHA256，不发送明文口令），
 * 因此即使运行环境没有任何第三方 SASL Provider 也能完成认证；其他机制是否
 * 可用由 {@link #mechanisms()} 实际探测列出。</p>
 */
public final class SaslAuthenticator {

    private SaslAuthenticator() {
    }

    public record Mechanisms(List<String> client, List<String> server) {
        public Mechanisms {
            client = List.copyOf(client);
            server = List.copyOf(server);
        }

        public boolean supports(String mechanism) {
            return client.contains(mechanism) && server.contains(mechanism);
        }
    }

    public static Mechanisms mechanisms() {
        FcSaslProvider.ensureRegistered();
        return new Mechanisms(clientMechanisms(), serverMechanisms());
    }

    /**
     * 在本进程内完成一次完整 SASL 交换（等价于客户端与服务端逐 Token 传递）。
     *
     * @throws SaslException 机制不支持、凭据错误、用户不存在或授权身份不匹配
     */
    public static AuthIdentity authenticate(String mechanism, String username,
                                            char[] password, String authorizationId,
                                            String realm, UserDirectory directory)
            throws SaslException {
        FcSaslProvider.ensureRegistered();
        Mechanisms supported = mechanisms();
        if (mechanism == null || !supported.supports(mechanism)) {
            throw new SaslException("不支持的 SASL 机制：" + mechanism
                    + "（客户端 " + supported.client() + "，服务端 "
                    + supported.server() + "）");
        }
        Map<String, String> props = Map.of();
        CallbackHandler clientHandler = LocalCallbacks.client(username, password, realm);
        CallbackHandler serverHandler = LocalCallbacks.server(directory, realm);
        SaslClient client = Sasl.createSaslClient(new String[] {mechanism},
                authorizationId, "frontier-chronicle", "localhost", props, clientHandler);
        SaslServer server = Sasl.createSaslServer(mechanism, "frontier-chronicle",
                "localhost", props, serverHandler);
        if (client == null || server == null) {
            throw new SaslException("SASL 提供者无法创建机制：" + mechanism);
        }
        try {
            byte[] token = new byte[0];
            int rounds = 0;
            while (!server.isComplete()) {
                if (++rounds > 8) {
                    throw new SaslException("SASL 认证超过 8 轮，已中止");
                }
                byte[] challenge = server.evaluateResponse(token);
                if (server.isComplete()) {
                    client.evaluateChallenge(challenge == null ? new byte[0] : challenge);
                    break;
                }
                token = client.evaluateChallenge(challenge);
            }
            if (!client.isComplete()) {
                throw new SaslException("SASL 客户端未完成认证");
            }
            String authz = server.getAuthorizationID();
            if (authz == null || authz.isBlank()) {
                authz = username;
            }
            return new AuthIdentity(username, authz, mechanism, realm, Instant.now());
        } finally {
            client.dispose();
            server.dispose();
        }
    }

    /** 便捷入口：使用本地目录完成 FC-CHALLENGE。 */
    public static AuthIdentity authenticate(String username, char[] password,
                                            UserDirectory directory) throws SaslException {
        return authenticate(FcSaslProvider.MECHANISM, username, password,
                null, "frontier", directory);
    }

    private static List<String> clientMechanisms() {
        TreeSet<String> names = new TreeSet<>();
        Enumeration<SaslClientFactory> factories = Sasl.getSaslClientFactories();
        while (factories.hasMoreElements()) {
            SaslClientFactory factory = factories.nextElement();
            String[] mechanisms = factory.getMechanismNames(null);
            if (mechanisms != null) {
                for (String mechanism : mechanisms) {
                    names.add(mechanism);
                }
            }
        }
        return new ArrayList<>(names);
    }

    private static List<String> serverMechanisms() {
        TreeSet<String> names = new TreeSet<>();
        Enumeration<SaslServerFactory> factories = Sasl.getSaslServerFactories();
        while (factories.hasMoreElements()) {
            SaslServerFactory factory = factories.nextElement();
            String[] mechanisms = factory.getMechanismNames(null);
            if (mechanisms != null) {
                for (String mechanism : mechanisms) {
                    names.add(mechanism);
                }
            }
        }
        return new ArrayList<>(names);
    }
}
