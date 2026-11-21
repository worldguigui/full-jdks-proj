package fc.extensions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import javax.security.auth.x500.X500Principal;

/**
 * 本机信任根与 X.509 证书签发。
 *
 * <p>远程端点（聊天中继、管理通道）默认只绑定回环地址，本类为「本地可信 ≠ 远程可信」
 * 提供证书边界：生成自签根、可选的中间证书，以及服务端/客户端叶子证书。
 * 只使用公开的 {@code java.security} / {@code java.security.cert} API：
 * 证书结构由本类按 RFC 5280 手工做 DER 编码，再用 {@link CertificateFactory} 解析，
 * 不依赖 JDK 内部 {@code sun.security.x509}。</p>
 *
 * <p>证书链的构建与校验见 {@link CertificateChains}；密钥库与 TLS 上下文见
 * {@link SecureChannel}。</p>
 */
public final class TrustAuthority {

    /** 本机信任库的默认口令；仅保护本地演示用的自签材料。 */
    public static final String DEFAULT_PASSWORD = "frontier-chronicle";

    /** 证书用途；决定基本约束、密钥用法与扩展密钥用法。 */
    public enum Purpose {
        /** 自签根证书。 */
        ROOT,
        /** 由根签发的中间 CA。 */
        INTERMEDIATE,
        /** 服务端叶子证书（serverAuth）。 */
        SERVER,
        /** 客户端叶子证书（clientAuth）。 */
        CLIENT
    }

    /** 一份签发结果：证书与其私钥。 */
    public record KeyMaterial(X509Certificate certificate, KeyPair keyPair) {
    }

    private static final String SHA256_WITH_RSA = "1.2.840.113549.1.1.11";
    private static final String OID_BASIC_CONSTRAINTS = "2.5.29.19";
    private static final String OID_KEY_USAGE = "2.5.29.15";
    private static final String OID_EXT_KEY_USAGE = "2.5.29.37";
    private static final String OID_SUBJECT_ALT_NAME = "2.5.29.17";
    private static final String OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1";
    private static final String OID_CLIENT_AUTH = "1.3.6.1.5.5.7.3.2";
    private static final DateTimeFormatter UTC_TIME =
            DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);

    private TrustAuthority() {
    }

    /** 生成 2048 位 RSA 密钥对。 */
    public static KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** 自签根证书，有效期 10 年。 */
    public static KeyMaterial root(String commonName) throws GeneralSecurityException {
        Instant now = Instant.now();
        return issue(Purpose.ROOT, null, commonName, now.minus(Duration.ofHours(1)),
                now.plus(Duration.ofDays(3_650)));
    }

    /** 由根证书签发中间 CA，有效期 5 年。 */
    public static KeyMaterial intermediate(KeyMaterial issuer, String commonName)
            throws GeneralSecurityException {
        Instant now = Instant.now();
        return issue(Purpose.INTERMEDIATE, issuer, commonName, now.minus(Duration.ofHours(1)),
                now.plus(Duration.ofDays(1_825)));
    }

    /** 由任意 CA 签发服务端证书，有效期 1 年；主题备用名固定为 localhost。 */
    public static KeyMaterial server(KeyMaterial issuer, String commonName)
            throws GeneralSecurityException {
        Instant now = Instant.now();
        return issue(Purpose.SERVER, issuer, commonName, now.minus(Duration.ofHours(1)),
                now.plus(Duration.ofDays(365)));
    }

    /** 由任意 CA 签发客户端证书，有效期 1 年。 */
    public static KeyMaterial client(KeyMaterial issuer, String commonName)
            throws GeneralSecurityException {
        Instant now = Instant.now();
        return issue(Purpose.CLIENT, issuer, commonName, now.minus(Duration.ofHours(1)),
                now.plus(Duration.ofDays(365)));
    }

    /**
     * 按用途签发证书。
     *
     * @param purpose   证书用途
     * @param issuer    签发者；{@link Purpose#ROOT} 时忽略并自签
     * @param commonName 主题 CN，不能为空
     * @param notBefore 生效时间
     * @param notAfter  失效时间，必须晚于生效时间
     */
    public static KeyMaterial issue(Purpose purpose, KeyMaterial issuer, String commonName,
                                    Instant notBefore, Instant notAfter)
            throws GeneralSecurityException {
        if (purpose == null) {
            throw new IllegalArgumentException("证书用途不能为空");
        }
        if (commonName == null || commonName.isBlank()) {
            throw new IllegalArgumentException("证书主题不能为空");
        }
        if (!notAfter.isAfter(notBefore)) {
            throw new IllegalArgumentException("证书失效时间必须晚于生效时间");
        }
        if (purpose != Purpose.ROOT && issuer == null) {
            throw new IllegalArgumentException("非根证书必须提供签发者");
        }

        KeyPair pair = generateKeyPair();
        X500Principal subject = new X500Principal("CN=" + commonName);
        X500Principal issuerName = purpose == Purpose.ROOT
                ? subject
                : issuer.certificate().getSubjectX500Principal();
        java.security.PrivateKey signingKey = purpose == Purpose.ROOT
                ? pair.getPrivate()
                : issuer.keyPair().getPrivate();

        byte[] algorithm = Der.sequence(Der.oid(SHA256_WITH_RSA), Der.nullValue());
        byte[] tbs = Der.sequence(
                Der.explicit(0, Der.integer(BigInteger.valueOf(2))),
                Der.integer(serialOf(pair.getPublic().getEncoded(), commonName, purpose)),
                algorithm,
                subjectName(issuerName),
                Der.sequence(Der.utcTime(notBefore), Der.utcTime(notAfter)),
                subjectName(subject),
                pair.getPublic().getEncoded(),
                Der.explicit(3, Der.sequence(extensions(purpose).toArray(byte[][]::new))));

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(signingKey);
        signer.update(tbs);
        byte[] signature = signer.sign();
        byte[] der = Der.sequence(tbs, algorithm, Der.bitString(signature, 0));

        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate certificate = (X509Certificate) factory.generateCertificate(
                new ByteArrayInputStream(der));
        return new KeyMaterial(certificate, pair);
    }

    /** 证书 SHA-256 指纹（小写十六进制，无分隔符）。 */
    public static String fingerprint(Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(certificate.getEncoded());
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法计算证书指纹：" + e.getMessage(), e);
        }
    }

    /** 叶子到根的证书链；参数按「叶子在前、根在后」给出。 */
    public static List<X509Certificate> chain(KeyMaterial... materials) {
        List<X509Certificate> chain = new ArrayList<>();
        for (KeyMaterial material : materials) {
            chain.add(material.certificate());
        }
        return List.copyOf(chain);
    }

    private static BigInteger serialOf(byte[] publicKey, String commonName, Purpose purpose)
            throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(publicKey);
        digest.update(commonName.getBytes(StandardCharsets.UTF_8));
        digest.update(purpose.name().getBytes(StandardCharsets.US_ASCII));
        byte[] head = Arrays.copyOf(digest.digest(), 8);
        BigInteger serial = new BigInteger(1, head);
        return serial.signum() == 0 ? BigInteger.ONE : serial;
    }

    private static byte[] subjectName(X500Principal name) {
        return name.getEncoded();
    }

    private static List<byte[]> extensions(Purpose purpose) {
        List<byte[]> list = new ArrayList<>();
        boolean ca = purpose == Purpose.ROOT || purpose == Purpose.INTERMEDIATE;
        list.add(Der.sequence(
                Der.oid(OID_BASIC_CONSTRAINTS),
                Der.booleanValue(true),
                Der.octetString(Der.sequence(
                        ca ? Der.booleanValue(true) : Der.booleanValue(false)))));
        int[] usage = switch (purpose) {
            case ROOT, INTERMEDIATE -> new int[] {5, 6};
            case SERVER -> new int[] {0, 2};
            case CLIENT -> new int[] {0};
        };
        list.add(Der.sequence(
                Der.oid(OID_KEY_USAGE),
                Der.booleanValue(true),
                Der.octetString(Der.bitString(keyUsageMask(usage), unusedBits(usage)))));
        switch (purpose) {
            case SERVER -> {
                list.add(Der.sequence(
                        Der.oid(OID_EXT_KEY_USAGE),
                        Der.octetString(Der.sequence(Der.oid(OID_SERVER_AUTH)))));
                list.add(Der.sequence(
                        Der.oid(OID_SUBJECT_ALT_NAME),
                        Der.octetString(Der.sequence(Der.dnsName("localhost")))));
            }
            case CLIENT -> list.add(Der.sequence(
                    Der.oid(OID_EXT_KEY_USAGE),
                    Der.octetString(Der.sequence(Der.oid(OID_CLIENT_AUTH)))));
            default -> {
                // 根与中间 CA 不限制扩展用途。
            }
        }
        return list;
    }

    private static byte[] keyUsageMask(int[] bits) {
        int maxBit = 0;
        for (int bit : bits) {
            maxBit = Math.max(maxBit, bit);
        }
        byte[] mask = new byte[maxBit / 8 + 1];
        for (int bit : bits) {
            mask[bit / 8] |= (byte) (0x80 >>> (bit % 8));
        }
        return mask;
    }

    private static int unusedBits(int[] bits) {
        int maxBit = 0;
        for (int bit : bits) {
            maxBit = Math.max(maxBit, bit);
        }
        return 7 - (maxBit % 8);
    }

    /** 最小 DER 编码器：只覆盖证书需要的几种类型。 */
    private static final class Der {

        private Der() {
        }

        static byte[] sequence(byte[]... parts) {
            return tlv(0x30, concat(parts));
        }

        static byte[] explicit(int tagNumber, byte[] content) {
            return tlv(0xA0 | tagNumber, content);
        }

        static byte[] integer(BigInteger value) {
            return tlv(0x02, value.toByteArray());
        }

        static byte[] booleanValue(boolean value) {
            return tlv(0x01, new byte[] {(byte) (value ? 0xFF : 0x00)});
        }

        static byte[] nullValue() {
            return tlv(0x05, new byte[0]);
        }

        static byte[] octetString(byte[] content) {
            return tlv(0x04, content);
        }

        static byte[] bitString(byte[] content, int unusedBits) {
            byte[] body = new byte[content.length + 1];
            body[0] = (byte) unusedBits;
            System.arraycopy(content, 0, body, 1, content.length);
            return tlv(0x03, body);
        }

        static byte[] ia5String(String text) {
            return tlv(0x16, text.getBytes(StandardCharsets.US_ASCII));
        }

        /** GeneralName 的 dNSName 分支：[2] IMPLICIT IA5String。 */
        static byte[] dnsName(String text) {
            return tlv(0x82, text.getBytes(StandardCharsets.US_ASCII));
        }

        static byte[] utcTime(Instant instant) {
            return tlv(0x17, UTC_TIME.format(instant).getBytes(StandardCharsets.US_ASCII));
        }

        static byte[] oid(String dotted) {
            String[] parts = dotted.split("\\.");
            if (parts.length < 2) {
                throw new IllegalArgumentException("OID 至少需要两段：" + dotted);
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            long first = Long.parseLong(parts[0]) * 40 + Long.parseLong(parts[1]);
            writeBase128(body, first);
            for (int i = 2; i < parts.length; i++) {
                writeBase128(body, Long.parseLong(parts[i]));
            }
            return tlv(0x06, body.toByteArray());
        }

        private static void writeBase128(ByteArrayOutputStream out, long value) {
            int groups = 1;
            for (long v = value; v >= 0x80; v >>>= 7) {
                groups++;
            }
            for (int i = groups - 1; i >= 0; i--) {
                int group = (int) ((value >>> (7 * i)) & 0x7F);
                out.write(i == 0 ? group : group | 0x80);
            }
        }

        private static byte[] tlv(int tag, byte[] content) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 6);
            out.write(tag);
            writeLength(out, content.length);
            out.write(content, 0, content.length);
            return out.toByteArray();
        }

        private static void writeLength(ByteArrayOutputStream out, int length) {
            if (length < 0x80) {
                out.write(length);
                return;
            }
            int bytes = 0;
            for (int v = length; v > 0; v >>>= 8) {
                bytes++;
            }
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) {
                out.write((length >>> (8 * i)) & 0xFF);
            }
        }

        private static byte[] concat(byte[]... parts) {
            int size = 0;
            for (byte[] part : parts) {
                size += part.length;
            }
            byte[] all = new byte[size];
            int at = 0;
            for (byte[] part : parts) {
                System.arraycopy(part, 0, all, at, part.length);
                at += part.length;
            }
            return all;
        }
    }
}
