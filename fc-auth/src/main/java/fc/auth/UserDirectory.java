package fc.auth;

import java.util.Map;
import java.util.Optional;

/** 本地认证目录：SASL 服务端通过 CallbackHandler 查询口令与授权关系。 */
public interface UserDirectory {

    Optional<String> password(String username, String realm);

    default boolean authorize(String authenticationId, String authorizationId) {
        return authorizationId == null || authorizationId.isBlank()
                || authorizationId.equals(authenticationId);
    }

    /** 从不可变 Map 创建演示/测试目录。 */
    static UserDirectory of(Map<String, String> users) {
        Map<String, String> copy = Map.copyOf(users);
        return (username, realm) -> Optional.ofNullable(copy.get(username));
    }
}
