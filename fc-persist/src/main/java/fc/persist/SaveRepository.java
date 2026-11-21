package fc.persist;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * 存档仓库：把一局游戏压缩为可校验、可选加密的 JAR 存档。
 *
 * <p>本项目采用设计稿允许的「种子 + 操作日志重放」路线：存档保存初始世界来源、
 * 存档时状态哈希与全部命令；读档时从同一种子重新装配并按序重放，最后用
 * SHA-256 校验是否恢复到逐字节相同的状态。相比直接序列化整个对象图，它不会
 * 把 sealed 领域模型、账本与 sealed 事件的可变实现细节耦合进磁盘格式。</p>
 *
 * <p>文件结构（单文件 JAR，天然可压缩）：</p>
 * <ul>
 *   <li>{@code META-INF/MANIFEST.MF}：格式标记；</li>
 *   <li>{@code save.properties}：版本、校验和、加密参数；</li>
 *   <li>{@code game.bin}：明文或 AES-GCM 密文的游戏负载。</li>
 * </ul>
 *
 * <p>口令使用 PBKDF2WithHmacSHA256 派生 256 位密钥，AES/GCM/NoPadding 同时提供
 * 机密性与完整性；无口令存档仍带 SHA-256，用于检测损坏。</p>
 */
public final class SaveRepository {

    public static final int FORMAT_VERSION = 1;
    public static final String EXTENSION = ".fcsave";

    private static final String MAGIC = "FCSAVE";
    private static final String ENTRY_PROPS = "save.properties";
    private static final String ENTRY_GAME = "game.bin";
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int MAX_COMMANDS = 1_000_000;
    private static final int MAX_ENTRY_BYTES = 64 * 1024 * 1024;
    private static final java.util.regex.Pattern SLOT =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final Path directory;
    private final SecureRandom random = new SecureRandom();

    public SaveRepository(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    /** 默认存档目录：当前工作目录下的 {@code saves/}。 */
    public static SaveRepository defaultRepository() {
        return new SaveRepository(Path.of("saves"));
    }

    public Path directory() {
        return directory;
    }

    /** 校验并解析槽位名，禁止路径穿越。 */
    public Path resolve(String slot) {
        if (slot == null || !SLOT.matcher(slot).matches()) {
            throw new IllegalArgumentException("槽位名只能包含字母、数字、下划线或短横线（1–32 字符）");
        }
        return directory.resolve(slot + EXTENSION);
    }

    /** 写入存档：先写临时文件并强制落盘，再原子替换目标。 */
    public Path save(String slot, SavePayload payload, String password) throws IOException {
        Path target = resolve(slot);
        Files.createDirectories(directory);
        byte[] plain = encode(payload);
        String checksum = sha256Hex(plain);

        Properties props = new Properties();
        props.setProperty("format", Integer.toString(FORMAT_VERSION));
        props.setProperty("magic", MAGIC);
        props.setProperty("checksum", checksum);
        props.setProperty("day", Long.toString(payload.day()));
        props.setProperty("world", payload.worldSource());
        props.setProperty("commandCount", Integer.toString(payload.commands().size()));
        byte[] stored = plain;
        if (password != null && !password.isEmpty()) {
            byte[] salt = new byte[SALT_BYTES];
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(salt);
            random.nextBytes(iv);
            stored = encrypt(plain, password, salt, iv, props);
            props.setProperty("encrypted", "true");
            props.setProperty("salt", Base64.getEncoder().encodeToString(salt));
            props.setProperty("iv", Base64.getEncoder().encodeToString(iv));
            props.setProperty("iterations", Integer.toString(PBKDF2_ITERATIONS));
        } else {
            props.setProperty("encrypted", "false");
        }

        Path tmp = Files.createTempFile(directory, slot + "-", ".tmp");
        try {
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Created-By", "Frontier Chronicle SaveRepository");
            try (OutputStream file = Files.newOutputStream(tmp);
                 JarOutputStream jar = new JarOutputStream(file, manifest)) {
                jar.putNextEntry(new JarEntry(ENTRY_PROPS));
                props.store(jar, "Frontier Chronicle save metadata");
                jar.closeEntry();
                jar.putNextEntry(new JarEntry(ENTRY_GAME));
                jar.write(stored);
                jar.closeEntry();
            }
            // 落盘后再替换，避免断电留下半个存档。
            try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
                    tmp, java.nio.file.StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return target;
    }

    /** 读取并校验存档；口令错误、格式不符或校验和不一致都会抛出 SaveException。 */
    public SavePayload load(String slot, String password) throws IOException {
        Path file = resolve(slot);
        if (!Files.isRegularFile(file)) {
            throw new SaveException("存档不存在: " + slot);
        }
        byte[] stored;
        Properties props;
        try (JarFile jar = new JarFile(file.toFile(), true)) {
            JarEntry pe = jar.getJarEntry(ENTRY_PROPS);
            JarEntry ge = jar.getJarEntry(ENTRY_GAME);
            if (pe == null || ge == null) {
                throw new SaveException("存档缺少必需条目");
            }
            props = new Properties();
            try (InputStream in = jar.getInputStream(pe);
                 InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            stored = readLimited(jar.getInputStream(ge));
            // 显式读取所有条目，触发 JAR 签名验证（若存档带签名）。
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (!e.isDirectory()) {
                    try (InputStream ignored = jar.getInputStream(e)) {
                        ignored.transferTo(OutputStream.nullOutputStream());
                    }
                }
            }
        }
        if (!MAGIC.equals(props.getProperty("magic"))) {
            throw new SaveException("不是 Frontier Chronicle 存档");
        }
        int version = intProp(props, "format");
        if (version != FORMAT_VERSION) {
            throw new SaveException("存档版本不兼容：文件 v" + version
                    + "，程序 v" + FORMAT_VERSION);
        }
        boolean encrypted = Boolean.parseBoolean(props.getProperty("encrypted", "false"));
        byte[] plain = stored;
        if (encrypted) {
            if (password == null || password.isEmpty()) {
                throw new SaveException("该存档已加密，需要口令");
            }
            try {
                plain = decrypt(stored, password, props);
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                throw new SaveException("解密失败（口令错误或存档损坏）", e);
            }
        }
        String expected = props.getProperty("checksum", "");
        String actual = sha256Hex(plain);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new SaveException("存档校验和不一致，文件可能已损坏");
        }
        return decode(plain);
    }

    /** 列出全部槽位名（按字母序）。 */
    public List<String> slots() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(EXTENSION))
                    .map(n -> n.substring(0, n.length() - EXTENSION.length()))
                    .sorted(Comparator.naturalOrder())
                    .forEach(out::add);
        }
        return out;
    }

    // ------------------------------------------------------------ 编码

    private static byte[] encode(SavePayload payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(FORMAT_VERSION);
            out.writeUTF(MAGIC);
            out.writeUTF(payload.worldSource());
            out.writeUTF(payload.stateHash());
            out.writeLong(payload.day());
            out.writeInt(payload.commands().size());
            for (String command : payload.commands()) {
                if (command.getBytes(StandardCharsets.UTF_8).length > 60_000) {
                    throw new SaveException("命令过长，无法写入存档");
                }
                out.writeUTF(command);
            }
        }
        return bytes.toByteArray();
    }

    private static SavePayload decode(byte[] plain) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain))) {
            int version = in.readInt();
            if (version != FORMAT_VERSION) {
                throw new SaveException("负载版本不兼容: " + version);
            }
            if (!MAGIC.equals(in.readUTF())) {
                throw new SaveException("负载魔数不匹配");
            }
            String world = in.readUTF();
            String hash = in.readUTF();
            long day = in.readLong();
            int count = in.readInt();
            if (count < 0 || count > MAX_COMMANDS) {
                throw new SaveException("命令条数非法: " + count);
            }
            List<String> commands = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                commands.add(in.readUTF());
            }
            if (in.available() != 0) {
                throw new SaveException("存档尾部存在多余数据");
            }
            return new SavePayload(world, hash, day, commands);
        }
    }

    // ------------------------------------------------------------ 加密

    private static byte[] encrypt(byte[] plain, String password, byte[] salt, byte[] iv,
                                  Properties props) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), new GCMParameterSpec(
                    GCM_TAG_BITS, iv));
            props.setProperty("iterations", Integer.toString(PBKDF2_ITERATIONS));
            return cipher.doFinal(plain);
        } catch (GeneralSecurityException e) {
            throw new SaveException("当前 JDK 不支持 AES-GCM/PBKDF2", e);
        }
    }

    private static byte[] decrypt(byte[] cipherText, String password, Properties props)
            throws GeneralSecurityException, SaveException {
        byte[] salt = Base64.getDecoder().decode(props.getProperty("salt"));
        byte[] iv = Base64.getDecoder().decode(props.getProperty("iv"));
        int iterations = intProp(props, "iterations");
        if (iterations < 10_000 || iterations > 10_000_000) {
            throw new SaveException("PBKDF2 迭代次数非法: " + iterations);
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            SecretKeySpec key = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        } finally {
            spec.clearPassword();
        }
        return cipher.doFinal(cipherText);
    }

    private static SecretKeySpec deriveKey(String password, byte[] salt)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt,
                PBKDF2_ITERATIONS, 256);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
        } finally {
            spec.clearPassword();
        }
    }

    // ------------------------------------------------------------ 工具

    private static int intProp(Properties props, String key) throws SaveException {
        try {
            return Integer.parseInt(props.getProperty(key, "-1"));
        } catch (NumberFormatException e) {
            throw new SaveException("存档字段非法: " + key);
        }
    }

    private static String sha256Hex(byte[] data) throws SaveException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new SaveException("JDK 缺少 SHA-256", e);
        }
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        byte[] data = in.readNBytes(MAX_ENTRY_BYTES + 1);
        if (data.length > MAX_ENTRY_BYTES) {
            throw new SaveException("存档条目过大");
        }
        return data;
    }

    /** 供测试/诊断：把口令从内存中擦除。 */
    public static void wipe(char[] password) {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }
}
