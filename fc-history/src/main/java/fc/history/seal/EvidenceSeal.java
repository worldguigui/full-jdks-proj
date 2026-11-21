package fc.history.seal;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SealedObject;
import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.NotSerializableException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;

/**
 * 证据封存：用 PKCS#12 密钥库中的 AES-256 密钥把文件封成 {@code .sealed} 包。
 *
 * <p>密钥库只保存一个 {@code SecretKeyEntry}（别名 {@value #ALIAS}），文件本身用
 * {@code AES/GCM/NoPadding} 加密后交给 {@link SealedObject} 序列化；封存文件前有魔数
 * 头，反序列化时用 {@link ObjectInputFilter} 白名单，避免把任意序列化流当输入。</p>
 *
 * <p>密钥库口令由调用方提供；CLI 的默认口令仅用于本地演示，部署时应通过
 * {@code -Dfc.seal.password} 覆盖，或用独立口令工具管理。</p>
 */
public final class EvidenceSeal {

    /** 密钥别名。 */
    public static final String ALIAS = "evidence";
    /** 密钥库格式。 */
    public static final String KEYSTORE_TYPE = "PKCS12";

    private static final byte[] MAGIC = {
            'F', 'C', 'S', 'E', 'A', 'L', '1'};
    private static final int KEY_BITS = 256;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final ObjectInputFilter FILTER = ObjectInputFilter.Config.createFilter(
            "javax.crypto.SealedObject;java.lang.String;[B;!*");

    private EvidenceSeal() {
    }

    /** 封存结果。 */
    public record SealInfo(Path path, int bytes, String fingerprint) {
    }

    /** 密钥库中的密钥信息。 */
    public record KeyInfo(String alias, String algorithm, int bits, Instant created) {
    }

    /** 封存/解封失败。 */
    public static final class SealException extends IOException {
        private static final long serialVersionUID = 1L;

        public SealException(String message) {
            super(message);
        }

        public SealException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 打开已有密钥库；不存在时生成 AES-256 密钥并落盘。 */
    public static KeyStore loadOrCreate(Path keystore, char[] password) throws IOException {
        requirePassword(password);
        Path file = prepare(keystore);
        if (Files.exists(file)) {
            return load(file, password);
        }
        try {
            SecretKey key = generateKey();
            KeyStore store = KeyStore.getInstance(KEYSTORE_TYPE);
            store.load(null, password);
            store.setEntry(ALIAS, new KeyStore.SecretKeyEntry(key),
                    new KeyStore.PasswordProtection(password));
            try (OutputStream out = Files.newOutputStream(file)) {
                store.store(out, password);
            }
            restrictPermissions(file);
            return store;
        } catch (GeneralSecurityException e) {
            throw new SealException("密钥库创建失败：" + e.getMessage(), e);
        }
    }

    /** 读取已有密钥库，要求存在证据密钥。 */
    public static KeyStore load(Path keystore, char[] password) throws IOException {
        requirePassword(password);
        Path file = prepare(keystore);
        if (!Files.isRegularFile(file)) {
            throw new SealException("密钥库不存在：" + file);
        }
        try (InputStream in = Files.newInputStream(file)) {
            KeyStore store = KeyStore.getInstance(KEYSTORE_TYPE);
            try {
                store.load(in, password);
            } catch (IOException e) {
                throw new SealException("密钥库读取失败（口令错误或文件损坏）："
                        + e.getMessage(), e);
            }
            if (!store.containsAlias(ALIAS)) {
                throw new SealException("密钥库缺少别名 " + ALIAS);
            }
            return store;
        } catch (GeneralSecurityException e) {
            throw new SealException("密钥库读取失败（口令错误或文件损坏）："
                    + e.getMessage(), e);
        }
    }

    /** 读取证据密钥；密钥库口令错误时抛出 {@link SealException}。 */
    public static SecretKey evidenceKey(KeyStore store, char[] password) throws IOException {
        requirePassword(password);
        try {
            Key key = store.getKey(ALIAS, password);
            if (!(key instanceof SecretKey secretKey)) {
                throw new SealException("别名 " + ALIAS + " 不是对称密钥");
            }
            return secretKey;
        } catch (GeneralSecurityException e) {
            throw new SealException("证据密钥读取失败：" + e.getMessage(), e);
        }
    }

    /** 封存内存负载（{@code .sealed} 字节）。 */
    public static byte[] sealBytes(SecretKey key, byte[] payload) throws IOException {
        if (key == null) {
            throw new IllegalArgumentException("key 不能为空");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload 不能为空");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            SealedObject sealed = new SealedObject(
                    Base64.getEncoder().encodeToString(payload), cipher);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(MAGIC);
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(sealed);
            }
            return bytes.toByteArray();
        } catch (GeneralSecurityException | NotSerializableException e) {
            throw new SealException("封存失败：" + e.getMessage(), e);
        }
    }

    /** 解封内存负载；口令错误或密文损坏时抛出 {@link SealException}。 */
    public static byte[] openBytes(SecretKey key, byte[] sealed) throws IOException {
        if (key == null) {
            throw new IllegalArgumentException("key 不能为空");
        }
        if (sealed == null || sealed.length <= MAGIC.length) {
            throw new SealException("封存文件为空或过短");
        }
        if (!Arrays.equals(MAGIC, Arrays.copyOf(sealed, MAGIC.length))) {
            throw new SealException("不是本程序生成的封存文件（魔数不符）");
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(
                sealed, MAGIC.length, sealed.length - MAGIC.length))) {
            in.setObjectInputFilter(FILTER);
            Object object = in.readObject();
            if (!(object instanceof SealedObject sealedObject)) {
                throw new SealException("封存负载类型非法：" + object.getClass().getName());
            }
            Object content = sealedObject.getObject(key);
            if (!(content instanceof String text)) {
                throw new SealException("封存内容不是文本负载");
            }
            return Base64.getDecoder().decode(text);
        } catch (InvalidClassException e) {
            throw new SealException("封存负载被输入过滤器拒绝：" + e.getMessage(), e);
        } catch (ClassNotFoundException | NotSerializableException
                 | java.security.NoSuchAlgorithmException
                 | java.security.InvalidKeyException e) {
            throw new SealException("解封失败：" + e.getMessage(), e);
        } catch (IOException e) {
            throw new SealException("解密失败（密钥错误或密文被篡改）：" + e.getMessage(), e);
        }
    }

    /** 封存文件；先写同目录临时文件再原子替换。 */
    public static SealInfo sealFile(SecretKey key, Path input, Path output) throws IOException {
        if (input == null || !Files.isRegularFile(input)) {
            throw new SealException("待封存文件不存在：" + input);
        }
        byte[] payload = Files.readAllBytes(input);
        byte[] sealed = sealBytes(key, payload);
        Path target = prepare(output);
        Path temp = Files.createTempFile(target.getParent(), ".sealed-", ".tmp");
        try {
            Files.write(temp, sealed);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return new SealInfo(target, sealed.length, fingerprint(key));
    }

    /** 解封文件并写出明文。 */
    public static Path openFile(SecretKey key, Path sealed, Path output) throws IOException {
        if (sealed == null || !Files.isRegularFile(sealed)) {
            throw new SealException("封存文件不存在：" + sealed);
        }
        byte[] payload = openBytes(key, Files.readAllBytes(sealed));
        Path target = prepare(output);
        Path temp = Files.createTempFile(target.getParent(), ".opened-", ".tmp");
        try {
            Files.write(temp, payload);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return target;
    }

    /** 密钥材料的 SHA-256 指纹（十六进制）。 */
    public static String fingerprint(SecretKey key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(key.getEncoded()));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    /** 密钥库中证据密钥的算法、位数与创建时间。 */
    public static KeyInfo keyInfo(KeyStore store, char[] password) throws IOException {
        requirePassword(password);
        try {
            Key key = store.getKey(ALIAS, password);
            if (key == null) {
                throw new SealException("密钥库缺少别名 " + ALIAS);
            }
            java.security.cert.Certificate certificate = store.getCertificate(ALIAS);
            Instant created = store.getCreationDate(ALIAS) == null
                    ? Instant.EPOCH : store.getCreationDate(ALIAS).toInstant();
            String algorithm = certificate == null ? key.getAlgorithm()
                    : certificate.getPublicKey().getAlgorithm();
            int bits = key.getEncoded() == null ? 0 : key.getEncoded().length * 8;
            return new KeyInfo(ALIAS, algorithm, bits, created);
        } catch (GeneralSecurityException e) {
            throw new SealException("密钥信息读取失败：" + e.getMessage(), e);
        }
    }

    private static SecretKey generateKey() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(KEY_BITS);
        return generator.generateKey();
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("密钥库口令不能为空");
        }
    }

    private static Path prepare(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("路径不能为空");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.getParent() != null) {
            Files.createDirectories(normalized.getParent());
        }
        return normalized;
    }

    private static void restrictPermissions(Path file) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException | IOException e) {
            // Windows 或非 POSIX 文件系统：权限由部署方负责。
        }
    }

    /** 供文档与测试引用的 POSIX 权限集合名。 */
    public static String posixOwnerOnly() {
        return PosixFilePermissions.toString(EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }

    /** 把文本负载编码为 UTF-8 字节的便捷入口。 */
    public static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
