package fc.auth;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * FC-CHALLENGE 客户端：接收服务端 nonce，回传用户名、授权身份与
 * HMAC-SHA256(nonce|username|authz, password) 的响应。不发送明文口令。
 */
final class FcSaslClient implements SaslClient {

    private final String authorizationId;
    private final String protocol;
    private final String serverName;
    private final CallbackHandler callbackHandler;
    private int state;
    private boolean complete;
    private boolean disposed;

    FcSaslClient(String authorizationId, String protocol, String serverName,
                 CallbackHandler callbackHandler) {
        this.authorizationId = authorizationId;
        this.protocol = protocol;
        this.serverName = serverName;
        this.callbackHandler = callbackHandler;
    }

    @Override
    public String getMechanismName() {
        return FcSaslProvider.MECHANISM;
    }

    @Override
    public boolean hasInitialResponse() {
        return false;
    }

    @Override
    public byte[] evaluateChallenge(byte[] challenge) throws SaslException {
        ensureAlive();
        if (complete) {
            throw new SaslException("认证已完成，不能再接收挑战");
        }
        String text = challenge == null ? ""
                : new String(challenge, StandardCharsets.UTF_8);
        if (state == 0) {
            if (text.isBlank()) {
                // 协议允许客户端先请求挑战；不改变状态。
                return new byte[0];
            }
            state = 1;
        }
        if (state == 1) {
            if (!text.startsWith("FC1 ")) {
                throw new SaslException("无法识别的 FC-CHALLENGE 挑战");
            }
            String nonce = text.substring(4).strip();
            if (nonce.isEmpty()) {
                throw new SaslException("挑战缺少 nonce");
            }
            char[] password = password();
            try {
                String username = username();
                String authz = authorizationId == null || authorizationId.isBlank()
                        ? username : authorizationId;
                byte[] mac = SaslCrypto.hmac(password,
                        nonce + "|" + username + "|" + authz);
                state = 2;
                return ("FC1 " + b64(username) + " " + b64(authz) + " "
                        + Base64.getEncoder().encodeToString(mac))
                        .getBytes(StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(password, '\0');
            }
        }
        if (state == 2) {
            if (!text.startsWith("FC1 OK")) {
                throw new SaslException("认证未得到服务端确认");
            }
            complete = true;
            state = 3;
            return new byte[0];
        }
        throw new SaslException("FC-CHALLENGE 客户端状态非法");
    }

    @Override
    public boolean isComplete() {
        return complete;
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

    private String username() throws SaslException {
        NameCallback callback = new NameCallback("用户名");
        invoke(callback);
        if (callback.getName() == null || callback.getName().isBlank()) {
            throw new SaslException("客户端未提供用户名");
        }
        return callback.getName();
    }

    private char[] password() throws SaslException {
        PasswordCallback callback = new PasswordCallback("口令", false);
        invoke(callback);
        char[] password = callback.getPassword();
        if (password == null || password.length == 0) {
            throw new SaslException("客户端未提供口令");
        }
        callback.clearPassword();
        return password;
    }

    private void invoke(Callback callback) throws SaslException {
        try {
            callbackHandler.handle(new Callback[] {callback});
        } catch (Exception e) {
            throw new SaslException("客户端 CallbackHandler 失败：" + e.getMessage(), e);
        }
    }

    private void ensureAlive() throws SaslException {
        if (disposed) {
            throw new SaslException("SaslClient 已释放");
        }
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
