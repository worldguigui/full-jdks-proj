package fc.auth;

import javax.security.auth.callback.CallbackHandler;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslClientFactory;
import java.util.Map;

/** FC-CHALLENGE 客户端工厂。 */
public final class FcSaslClientFactory implements SaslClientFactory {

    @Override
    public SaslClient createSaslClient(String[] mechanisms, String authorizationId,
                                       String protocol, String serverName,
                                       Map<String, ?> props, CallbackHandler callbackHandler) {
        if (mechanisms == null) {
            return null;
        }
        for (String mechanism : mechanisms) {
            if (FcSaslProvider.MECHANISM.equalsIgnoreCase(mechanism)) {
                return new FcSaslClient(authorizationId,
                        protocol == null ? "frontier" : protocol,
                        serverName == null ? "localhost" : serverName,
                        callbackHandler);
            }
        }
        return null;
    }

    @Override
    public String[] getMechanismNames(Map<String, ?> props) {
        return new String[] {FcSaslProvider.MECHANISM};
    }
}
