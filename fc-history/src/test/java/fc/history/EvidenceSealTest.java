package fc.history;

import fc.history.seal.EvidenceSeal;
import fc.testkit.FcTest;
import fc.testkit.TestRunner;

import javax.crypto.SecretKey;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;

/** 证据封存：PKCS#12 密钥库、AES-GCM、SealedObject、输入过滤与错误路径。 */
public final class EvidenceSealTest {

    private static final char[] PASSWORD = "correct horse battery".toCharArray();

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(EvidenceSealTest.class))) {
            System.exit(1);
        }
    }

    private static Path tempDir() throws Exception {
        return Files.createTempDirectory("fc-seal-test-");
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static KeyStore store(Path dir) throws Exception {
        return EvidenceSeal.loadOrCreate(dir.resolve("keys/evidence.p12"), PASSWORD);
    }

    @FcTest
    public void sealAndOpenRoundTrip() throws Exception {
        Path dir = tempDir();
        try {
            SecretKey key = EvidenceSeal.evidenceKey(store(dir), PASSWORD);
            byte[] payload = "边境纪事：第三星历".getBytes(StandardCharsets.UTF_8);
            byte[] sealed = EvidenceSeal.sealBytes(key, payload);
            assert sealed.length > payload.length : sealed.length;
            assert !Arrays.equals(payload, sealed) : "封存字节不得等于明文";
            assert !new String(sealed, StandardCharsets.ISO_8859_1).contains("边境")
                    : "封存内容不得包含明文";
            assert Arrays.equals(payload, EvidenceSeal.openBytes(key, sealed));

            byte[] again = EvidenceSeal.sealBytes(key, payload);
            assert !Arrays.equals(sealed, again) : "GCM 随机 IV 应使两次封存不同";
            assert Arrays.equals(payload, EvidenceSeal.openBytes(key, again));

            String fingerprint = EvidenceSeal.fingerprint(key);
            assert fingerprint.length() == 64 : fingerprint;
            assert fingerprint.equals(EvidenceSeal.fingerprint(
                    EvidenceSeal.evidenceKey(store(dir), PASSWORD))) : "密钥应稳定";
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void fileRoundTripWritesAtomicOutput() throws Exception {
        Path dir = tempDir();
        try {
            SecretKey key = EvidenceSeal.evidenceKey(store(dir), PASSWORD);
            Path input = dir.resolve("report/notes.xml");
            Files.createDirectories(input.getParent());
            Files.writeString(input, "<note>原样往返</note>", StandardCharsets.UTF_8);

            EvidenceSeal.SealInfo info = EvidenceSeal.sealFile(key,
                    input, dir.resolve("out/notes.xml.sealed"));
            assert Files.isRegularFile(info.path());
            assert info.bytes() == Files.size(info.path()) : info.bytes();
            assert info.fingerprint().equals(EvidenceSeal.fingerprint(key));
            byte[] header = Arrays.copyOf(Files.readAllBytes(info.path()), 7);
            assert new String(header, StandardCharsets.US_ASCII).equals("FCSEAL1");

            Path opened = EvidenceSeal.openFile(key, info.path(),
                    dir.resolve("out/notes.opened.xml"));
            assert Files.readString(opened, StandardCharsets.UTF_8)
                    .equals("<note>原样往返</note>");
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void wrongKeyAndTamperingAreRejected() throws Exception {
        Path first = tempDir();
        Path second = tempDir();
        try {
            SecretKey key = EvidenceSeal.evidenceKey(store(first), PASSWORD);
            SecretKey other = EvidenceSeal.evidenceKey(store(second), PASSWORD);
            byte[] sealed = EvidenceSeal.sealBytes(key,
                    "机密".getBytes(StandardCharsets.UTF_8));

            boolean wrongKey = false;
            try {
                EvidenceSeal.openBytes(other, sealed);
            } catch (EvidenceSeal.SealException e) {
                wrongKey = true;
            }
            assert wrongKey : "另一把密钥必须解封失败";

            byte[] tampered = sealed.clone();
            tampered[tampered.length - 1] ^= 0x01;
            boolean changed = false;
            try {
                EvidenceSeal.openBytes(key, tampered);
            } catch (EvidenceSeal.SealException e) {
                changed = true;
            }
            assert changed : "篡改密文必须被 GCM 拒绝";

            boolean magic = false;
            try {
                EvidenceSeal.openBytes(key, "not-a-sealed-file".getBytes(
                        StandardCharsets.US_ASCII));
            } catch (EvidenceSeal.SealException e) {
                magic = true;
                assert e.getMessage().contains("魔数") : e.getMessage();
            }
            assert magic;
        } finally {
            deleteTree(first);
            deleteTree(second);
        }
    }

    @FcTest
    public void inputFilterRejectsForeignSerializedObjects() throws Exception {
        Path dir = tempDir();
        try {
            SecretKey key = EvidenceSeal.evidenceKey(store(dir), PASSWORD);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write("FCSEAL1".getBytes(StandardCharsets.US_ASCII));
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                HashMap<String, String> foreign = new HashMap<>();
                foreign.put("a", "b");
                out.writeObject(foreign);
            }
            boolean rejected = false;
            try {
                EvidenceSeal.openBytes(key, bytes.toByteArray());
            } catch (EvidenceSeal.SealException e) {
                rejected = true;
                assert e.getMessage().contains("过滤器") : e.getMessage();
            }
            assert rejected : "白名单之外的序列化类必须被拒绝";
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void keystorePersistsAndKeyInfoIsStable() throws Exception {
        Path dir = tempDir();
        try {
            KeyStore store = store(dir);
            SecretKey key = EvidenceSeal.evidenceKey(store, PASSWORD);
            KeyStore reloaded = EvidenceSeal.load(
                    dir.resolve("keys/evidence.p12"), PASSWORD);
            assert EvidenceSeal.fingerprint(key).equals(EvidenceSeal.fingerprint(
                    EvidenceSeal.evidenceKey(reloaded, PASSWORD))) : "重载密钥应一致";

            EvidenceSeal.KeyInfo info = EvidenceSeal.keyInfo(reloaded, PASSWORD);
            assert info.alias().equals(EvidenceSeal.ALIAS) : info;
            assert info.algorithm().equals("AES") : info;
            assert info.bits() == 256 : info;
            assert info.created() != null;

            boolean wrongPassword = false;
            try {
                EvidenceSeal.load(dir.resolve("keys/evidence.p12"),
                        "wrong password".toCharArray());
            } catch (EvidenceSeal.SealException e) {
                wrongPassword = true;
            }
            assert wrongPassword : "错误口令必须被拒绝";

            boolean missing = false;
            try {
                EvidenceSeal.load(dir.resolve("keys/none.p12"), PASSWORD);
            } catch (EvidenceSeal.SealException e) {
                missing = true;
                assert e.getMessage().contains("不存在") : e.getMessage();
            }
            assert missing;
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void invalidInputsAreRejected() throws Exception {
        Path dir = tempDir();
        try {
            boolean nullKey = false;
            try {
                EvidenceSeal.sealBytes(null, new byte[] {1});
            } catch (IllegalArgumentException e) {
                nullKey = true;
            }
            assert nullKey;

            SecretKey key = EvidenceSeal.evidenceKey(store(dir), PASSWORD);
            boolean nullPayload = false;
            try {
                EvidenceSeal.sealBytes(key, null);
            } catch (IllegalArgumentException e) {
                nullPayload = true;
            }
            assert nullPayload;

            boolean empty = false;
            try {
                EvidenceSeal.openBytes(key, new byte[3]);
            } catch (EvidenceSeal.SealException e) {
                empty = true;
            }
            assert empty;

            boolean emptyPassword = false;
            try {
                EvidenceSeal.loadOrCreate(dir.resolve("keys/empty.p12"), new char[0]);
            } catch (IllegalArgumentException e) {
                emptyPassword = true;
            }
            assert emptyPassword;

            boolean missingFileIn = false;
            try {
                EvidenceSeal.sealFile(key, dir.resolve("no-such.txt"),
                        dir.resolve("out.sealed"));
            } catch (EvidenceSeal.SealException e) {
                missingFileIn = true;
            }
            assert missingFileIn;
        } finally {
            deleteTree(dir);
        }
    }
}
