package fc.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.security.sasl.SaslException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** FC-CHALLENGE 使用的 HMAC-SHA256 与常量时间比较。 */
final class SaslCrypto {

    private SaslCrypto() {
    }

    static byte[] hmac(char[] password, String data) throws SaslException {
        try {
            byte[] key = new String(password).getBytes(StandardCharsets.UTF_8);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new SaslException("HMAC 计算失败", e);
        }
    }

    static boolean same(byte[] a, byte[] b) {
        return a != null && b != null && MessageDigest.isEqual(a, b);
    }
}
