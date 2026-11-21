package fc.auth;

import java.security.Provider;
import java.security.Security;

/** 注册 Frontier Chronicle 的 SASL 机制提供者（幂等）。 */
public final class FcSaslProvider extends Provider {

    public static final String NAME = "FrontierChronicleSasl";
    public static final String MECHANISM = "FC-CHALLENGE";

    public FcSaslProvider() {
        super(NAME, "1.0", "Frontier Chronicle SASL provider");
        put("SaslClientFactory." + MECHANISM, FcSaslClientFactory.class.getName());
        put("SaslServerFactory." + MECHANISM, FcSaslServerFactory.class.getName());
    }

    /** 注册到 JCA；重复调用无副作用。 */
    public static synchronized void ensureRegistered() {
        if (Security.getProvider(NAME) == null) {
            Security.addProvider(new FcSaslProvider());
        }
    }
}
