package fc.auth;

import javax.security.auth.callback.CallbackHandler;
import javax.security.sasl.SaslServer;
import javax.security.sasl.SaslServerFactory;
import java.util.Map;

/** FC-CHALLENGE 服务端工厂。 */
public final class FcSaslServerFactory implements SaslServerFactory {

    @Override
    public SaslServer createSaslServer(String mechanism, String protocol,
                                       String serverName, Map<String, ?> props,
                                       CallbackHandler callbackHandler) {
        if (!FcSaslProvider.MECHANISM.equalsIgnoreCase(mechanism)) {
            return null;
        }
        return new FcSaslServer(protocol == null ? "frontier" : protocol,
                serverName == null ? "localhost" : serverName, callbackHandler);
    }

    @Override
    public String[] getMechanismNames(Map<String, ?> props) {
        return new String[] {FcSaslProvider.MECHANISM};
    }
}
