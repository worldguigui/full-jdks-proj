package fc.auth;

import java.time.Instant;

/** 认证成功后交给应用层的统一身份上下文。 */
public record AuthIdentity(String authenticationId, String authorizationId,
                           String mechanism, String realm, Instant authenticatedAt) {

    public AuthIdentity {
        if (authenticationId == null || authenticationId.isBlank()) {
            throw new IllegalArgumentException("认证身份不能为空");
        }
        authorizationId = authorizationId == null || authorizationId.isBlank()
                ? authenticationId : authorizationId;
        mechanism = mechanism == null ? "" : mechanism;
        realm = realm == null ? "" : realm;
        authenticatedAt = authenticatedAt == null ? Instant.now() : authenticatedAt;
    }

    @Override
    public String toString() {
        return authenticationId + (authorizationId.equals(authenticationId)
                ? "" : " as " + authorizationId) + " [" + mechanism + "]";
    }
}
