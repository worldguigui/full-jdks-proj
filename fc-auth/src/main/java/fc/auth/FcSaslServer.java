package fc.auth;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.sasl.AuthorizeCallback;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * FC-CHALLENGE 服务端：发出随机 nonce，校验客户端 HMAC，并通过
 * CallbackHandler 查询口令与授权关系；成功后返回标准授权身份。
 */
final class FcSaslServer implements SaslServer {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String protocol;
    private final String serverName;
    private final CallbackHandler callbackHandler;
    private final byte[] nonce = new byte[18];
    private int state;
    private boolean complete;
    private boolean disposed;
    private String authorizationId = "";

    FcSaslServer(String protocol, String serverName, CallbackHandler callbackHandler) {
        this.protocol = protocol;
        this.serverName = serverName;
        this.callbackHandler = callbackHandler;
    }

    @Override
    public String getMechanismName() {
        return FcSaslProvider.MECHANISM;
    }

    @Override
    public byte[] evaluateResponse(byte[] response) throws SaslException {
        ensureAlive();
        if (complete) {
            throw new SaslException("认证已完成");
        }
        if (state == 0) {
            RANDOM.nextBytes(nonce);
            state = 1;
            return ("FC1 " + Base64.getEncoder().encodeToString(nonce))
                    .getBytes(StandardCharsets.UTF_8);
        }
        String text = response == null ? ""
                : new String(response, StandardCharsets.UTF_8);
        String[] parts = text.split(" ");
        if (parts.length != 4 || !"FC1".equals(parts[0])) {
            throw new SaslException("无法识别的 FC-CHALLENGE 响应");
        }
        String username;
        String requestedAuthz;
        byte[] actual;
        try {
            username = new String(Base64.getDecoder().decode(parts[1]),
                    StandardCharsets.UTF_8);
            requestedAuthz = new String(Base64.getDecoder().decode(parts[2]),
                    StandardCharsets.UTF_8);
            actual = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException e) {
            throw new SaslException("认证 Token 编码非法", e);
        }
        NameCallback nameCallback = new NameCallback("用户名", username);
        nameCallback.setName(username);
        PasswordCallback passwordCallback = new PasswordCallback("口令", false);
        AuthorizeCallback authorize = new AuthorizeCallback(username, requestedAuthz);
        try {
            callbackHandler.handle(new Callback[] {
                    nameCallback, passwordCallback, authorize});
        } catch (Exception e) {
            throw new SaslException("服务端 CallbackHandler 失败：" + e.getMessage(), e);
        }
        char[] password = passwordCallback.getPassword();
        if (password == null || password.length == 0) {
            throw new SaslException("未知用户或口令不可用：" + username);
        }
        try {
            byte[] expected = SaslCrypto.hmac(password,
                    Base64.getEncoder().encodeToString(nonce) + "|" + username + "|"
                            + requestedAuthz);
            if (!SaslCrypto.same(expected, actual)) {
                throw new SaslException("认证失败：凭据不匹配");
            }
        } finally {
            Arrays.fill(password, '\0');
            passwordCallback.clearPassword();
        }
        if (!authorize.isAuthorized()) {
            throw new SaslException("授权失败：" + username + " 不能冒充 "
                    + requestedAuthz);
        }
        authorizationId = authorize.getAuthorizedID() == null
                ? requestedAuthz : authorize.getAuthorizedID();
        complete = true;
        state = 2;
        return ("FC1 OK " + Base64.getEncoder().encodeToString(
                authorizationId.getBytes(StandardCharsets.UTF_8)))
                .getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean isComplete() {
        return complete;
    }

    @Override
    public String getAuthorizationID() {
        return authorizationId;
    }

    @Override
    public byte[] unwrap(byte[] incoming, int offset, int len) throws SaslException {
        throw new SaslException("FC-CHALLENGE 不提供安全层");
    }

    @Override
    public byte[] wrap(byte[] outgoing, int offset, int len) throws SaslException {
        throw new SaslException("FC-CHALLENGE 不提供安全层");
    }

    @Override
    public Object getNegotiatedProperty(String propName) {
        return null;
    }

    @Override
    public void dispose() {
        disposed = true;
        Arrays.fill(nonce, (byte) 0);
    }

    boolean isDisposed() {
        return disposed;
    }

    String protocol() {
        return protocol;
    }

    String serverName() {
        return serverName;
    }

    private void ensureAlive() throws SaslException {
        if (disposed) {
            throw new SaslException("SaslServer 已释放");
        }
    }
}
