package fc.auth;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.AuthorizeCallback;
import javax.security.sasl.RealmCallback;
import java.io.IOException;
import java.util.Optional;

/**
 * SASL 的本地 CallbackHandler：客户端提供用户名/口令/领域，服务端根据目录
 * 校验口令并处理授权身份。未支持的 Callback 明确抛出，而不是静默忽略。
 */
public final class LocalCallbacks implements CallbackHandler {

    private final boolean server;
    private final String username;
    private final char[] password;
    private final String realm;
    private final UserDirectory directory;
    private volatile String currentUser;

    private LocalCallbacks(boolean server, String username, char[] password,
                           String realm, UserDirectory directory) {
        this.server = server;
        this.username = username == null ? "" : username;
        this.password = password == null ? new char[0] : password.clone();
        this.realm = realm == null ? "frontier" : realm;
        this.directory = directory;
    }

    public static LocalCallbacks client(String username, char[] password, String realm) {
        return new LocalCallbacks(false, username, password, realm, null);
    }

    public static LocalCallbacks server(UserDirectory directory, String realm) {
        return new LocalCallbacks(true, null, null, realm, directory);
    }

    @Override
    public void handle(Callback[] callbacks)
            throws IOException, UnsupportedCallbackException {
        for (Callback callback : callbacks) {
            switch (callback) {
                case NameCallback name -> {
                    if (server) {
                        String value = name.getName();
                        if ((value == null || value.isBlank())
                                && name.getDefaultName() != null) {
                            value = name.getDefaultName();
                        }
                        if (value != null && !value.isBlank()) {
                            currentUser = value;
                            name.setName(value);
                        }
                    } else {
                        name.setName(username);
                    }
                }
                case PasswordCallback passwordCallback -> {
                    if (server) {
                        String name = currentUser == null ? username : currentUser;
                        Optional<String> stored = directory.password(name, realm);
                        if (stored.isEmpty()) {
                            throw new UnsupportedCallbackException(passwordCallback,
                                    "未知用户：" + name);
                        }
                        passwordCallback.setPassword(stored.get().toCharArray());
                    } else {
                        passwordCallback.setPassword(password.clone());
                    }
                }
                case RealmCallback realmCallback -> realmCallback.setText(realm);
                case AuthorizeCallback authorize -> {
                    if (!server) {
                        authorize.setAuthorized(true);
                    } else {
                        boolean allowed = directory.authorize(authorize.getAuthenticationID(),
                                authorize.getAuthorizationID());
                        authorize.setAuthorized(allowed);
                        if (allowed) {
                            authorize.setAuthorizedID(authorize.getAuthorizationID());
                        }
                    }
                }
                default -> throw new UnsupportedCallbackException(callback,
                        "不支持的 SASL Callback：" + callback.getClass().getName());
            }
        }
    }
}
