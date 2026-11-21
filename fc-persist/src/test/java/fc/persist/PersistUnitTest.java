package fc.persist;

import fc.testkit.FcTest;
import fc.testkit.TestRunner;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.prefs.Preferences;

/** fc-persist 单元测试：存档格式、版本拒绝、本地化与偏好存储。 */
public final class PersistUnitTest {

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(PersistUnitTest.class))) {
            System.exit(1);
        }
    }

    private static Path tempDir() throws Exception {
        return Files.createTempDirectory("fc-persist-unit-");
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

    @FcTest
    public void payloadRoundTripsThroughRepository() throws Exception {
        Path dir = tempDir();
        try {
            SaveRepository repo = new SaveRepository(dir);
            SavePayload payload = new SavePayload("resource:world/default-world.xml",
                    "a".repeat(64), 12,
                    List.of("next 1", "trade buy FOOD 10", "研究 中文 命令"));
            Path file = repo.save("slot_1", payload, null);
            assert Files.isRegularFile(file);
            assert file.getFileName().toString().equals("slot_1.fcsave");

            SavePayload loaded = repo.load("slot_1", null);
            assert loaded.equals(payload) : loaded;
            assert loaded.commands().get(2).equals("研究 中文 命令");
            assert repo.slots().equals(List.of("slot_1"));

            // 覆盖写同一槽位应原子替换，不留下临时文件。
            SavePayload next = new SavePayload(payload.worldSource(), payload.stateHash(),
                    payload.day() + 1, payload.commands());
            repo.save("slot_1", next, null);
            assert repo.load("slot_1", null).day() == 13;
            try (var files = Files.list(dir)) {
                assert files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp"));
            }
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void futureFormatVersionIsRejected() throws Exception {
        Path dir = tempDir();
        try {
            SaveRepository repo = new SaveRepository(dir);
            Path file = repo.resolve("future");
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Properties props = new Properties();
            props.setProperty("format", "999");
            props.setProperty("magic", "FCSAVE");
            props.setProperty("checksum", "00");
            props.setProperty("encrypted", "false");
            try (OutputStream out = Files.newOutputStream(file);
                 JarOutputStream jar = new JarOutputStream(out)) {
                jar.putNextEntry(new JarEntry("save.properties"));
                props.store(new java.io.OutputStreamWriter(jar, StandardCharsets.UTF_8), null);
                jar.closeEntry();
                jar.putNextEntry(new JarEntry("game.bin"));
                jar.write(new byte[]{1, 2, 3});
                jar.closeEntry();
            }
            try {
                repo.load("future", null);
                throw new AssertionError("未来版本应被拒绝");
            } catch (SaveException expected) {
                assert expected.getMessage().contains("版本") : expected.getMessage();
            }
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void encryptedPayloadNeedsPasswordAndDetectsTampering() throws Exception {
        Path dir = tempDir();
        try {
            SaveRepository repo = new SaveRepository(dir);
            SavePayload payload = new SavePayload("file:C:/worlds/x.xml", "hash", 3,
                    List.of("next 1"));
            repo.save("cipher", payload, "p@ss");
            assert repo.load("cipher", "p@ss").equals(payload);

            // 用错误口令重写密文：GCM 完整性校验必须拒绝。
            Path file = repo.resolve("cipher");
            Properties props;
            byte[] cipher;
            try (JarFile jar = new JarFile(file.toFile())) {
                props = new Properties();
                try (InputStream in = jar.getInputStream(jar.getJarEntry("save.properties"))) {
                    props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                }
                try (InputStream in = jar.getInputStream(jar.getJarEntry("game.bin"))) {
                    cipher = in.readAllBytes();
                }
            }
            cipher[cipher.length - 1] ^= 0x01;
            try (OutputStream out = Files.newOutputStream(file);
                 JarOutputStream jar = new JarOutputStream(out)) {
                jar.putNextEntry(new JarEntry("save.properties"));
                props.store(new java.io.OutputStreamWriter(jar, StandardCharsets.UTF_8), null);
                jar.closeEntry();
                jar.putNextEntry(new JarEntry("game.bin"));
                jar.write(cipher);
                jar.closeEntry();
            }
            try {
                repo.load("cipher", "p@ss");
                throw new AssertionError("被篡改的密文应被拒绝");
            } catch (SaveException expected) {
                assert expected.getMessage().contains("解密失败") : expected.getMessage();
            }
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void localizationProvidesChineseAndEnglish() {
        Localization zh = Localization.of(Locale.SIMPLIFIED_CHINESE);
        Localization en = Localization.of(Locale.ENGLISH);
        assert "边境纪事".equals(zh.text("app.title")) : zh.text("app.title");
        assert "Frontier Chronicle".equals(en.text("app.title"));
        assert zh.format("save.saved", "auto", "第 3 天").contains("已保存槽位 auto");
        assert en.format("save.saved", "auto", "day 3").contains("Saved slot auto");
        assert zh.has("save.checksum");
        assert "!missing.key!".equals(en.text("missing.key"));
    }

    @FcTest
    public void preferencesPersistAndDeleteNode() throws Exception {
        String profile = "test-" + System.nanoTime();
        String path = "/fc/frontier-chronicle/" + profile;
        PreferenceStore store = new PreferenceStore(Preferences.userRoot().node(path));
        try {
            assert store.get("language", "unset").equals("unset");
            store.put("language", "zh-CN");
            store.putInt("volume", 60);
            store.putBoolean("fullscreen", true);
            store.flush();
            assert store.keys().equals(List.of("fullscreen", "language", "volume"))
                    : store.keys();
            assert store.getInt("volume", 0) == 60;
            assert store.getBoolean("fullscreen", false);

            // 同一节点的新实例应读到持久化值。
            PreferenceStore again = new PreferenceStore(Preferences.userRoot().node(path));
            assert again.get("language", "").equals("zh-CN");
            assert again.language().getLanguage().equals("zh");
            again.setLanguage(Locale.ENGLISH);
            assert again.language().getLanguage().equals("en");

            try {
                store.put("", "x");
                throw new AssertionError("空键应被拒绝");
            } catch (IllegalArgumentException expected) {
                assert expected.getMessage().contains("键");
            }
        } finally {
            store.delete();
        }
        assert !Preferences.userRoot().nodeExists(path) : "测试节点应已删除";
    }

    @FcTest
    public void profileNamesAreValidated() {
        try {
            PreferenceStore.user("../escape");
            throw new AssertionError("非法档案名应被拒绝");
        } catch (IllegalArgumentException expected) {
            assert expected.getMessage().contains("档案名");
        }
        try {
            PreferenceStore.user("a".repeat(33));
            throw new AssertionError("过长档案名应被拒绝");
        } catch (IllegalArgumentException expected) {
            assert expected.getMessage().contains("档案名");
        }
    }
}
