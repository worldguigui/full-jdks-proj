package fc.runtime;

import fc.persist.SaveException;
import fc.persist.SavePayload;
import fc.persist.SaveRepository;
import fc.sim.StateHasher;
import fc.testkit.FcTest;
import fc.testkit.TestRunner;
import fc.world.PendingDecision;
import fc.world.WorldState;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * M5 存档测试：重放读档的状态哈希一致性、加密、损坏检测与槽位管理。
 *
 * <p>存档不序列化对象图，而是保存世界来源 + 命令历史并在读档时重放；
 * 因此这里最重要的断言是「读档后的 {@link StateHasher} 与存档时完全相同」。</p>
 */
public final class PersistenceTest {

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(PersistenceTest.class))) {
            System.exit(1);
        }
    }

    /** 推进一天；出现待决决策时依次选择接受/拒绝，保证重放路径确定。 */
    private static void step(RuntimeSession session) {
        WorldState state = session.state();
        while (state.hasPendingDecision()) {
            PendingDecision d = state.pendingDecisions().get(0);
            PendingDecision.Option pick = d.options().stream()
                    .filter(o -> o.effect() == PendingDecision.Effect.ACCEPT)
                    .findFirst()
                    .or(() -> d.options().stream()
                            .filter(o -> o.effect() == PendingDecision.Effect.DECLINE)
                            .findFirst())
                    .orElse(d.options().get(0));
            session.execute("decide " + d.id().id() + " " + pick.id());
        }
        session.execute("next 1");
    }

    private static Path tempDir() throws Exception {
        return Files.createTempDirectory("fc-persist-test-");
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
    public void plainSaveReplaysToIdenticalHash() throws Exception {
        Path dir = tempDir();
        try {
            RuntimeSession original = RuntimeSession.create(new String[0]);
            original.execute("map");
            original.execute("scan 6");
            step(original);
            step(original);
            original.execute("sail 1 2");
            step(original);

            String hashBefore = StateHasher.hash(original.state());
            long dayBefore = original.state().now().day();
            int commands = original.commandHistory().size();
            assert commands > 0 : "推进与操作命令应进入重放历史";

            SaveRepository repo = new SaveRepository(dir);
            original.save(repo, "slot-1", null);

            RuntimeSession restored = RuntimeSession.load(repo, "slot-1", null);
            assert hashBefore.equals(StateHasher.hash(restored.state()))
                    : "读档后状态哈希必须与存档时一致";
            assert restored.state().now().day() == dayBefore;
            assert restored.commandHistory().equals(original.commandHistory())
                    : "重放应重建同一命令历史";
            assert restored.worldSource().equals(original.worldSource());
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void encryptedSaveRequiresCorrectPassword() throws Exception {
        Path dir = tempDir();
        try {
            RuntimeSession original = RuntimeSession.create(new String[0]);
            step(original);
            String expected = StateHasher.hash(original.state());

            SaveRepository repo = new SaveRepository(dir);
            original.save(repo, "secret", "边境口令 42");

            RuntimeSession restored = RuntimeSession.load(repo, "secret", "边境口令 42");
            assert expected.equals(StateHasher.hash(restored.state()));

            expectSaveError(() -> RuntimeSession.load(repo, "secret", "错误口令"),
                    "口令错误应被拒绝");
            expectSaveError(() -> RuntimeSession.load(repo, "secret", null),
                    "加密存档缺少口令应被拒绝");
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void corruptedSaveIsRejectedByChecksum() throws Exception {
        Path dir = tempDir();
        try {
            RuntimeSession original = RuntimeSession.create(new String[0]);
            original.execute("next 1");
            SaveRepository repo = new SaveRepository(dir);
            original.save(repo, "corrupt", null);
            Path file = repo.resolve("corrupt");

            // 保留 JAR 结构，只翻转 game.bin 中的一个字节：CRC 可能仍可读，
            // 但 SHA-256 校验和必须拒绝该负载。
            byte[] game;
            try (JarFile jar = new JarFile(file.toFile())) {
                try (InputStream in = jar.getInputStream(jar.getJarEntry("game.bin"))) {
                    game = in.readAllBytes();
                }
            }
            game[game.length / 2] ^= 0x5A;
            Path damaged = dir.resolve("damaged.fcsave");
            try (JarFile jar = new JarFile(file.toFile());
                 OutputStream out = Files.newOutputStream(damaged);
                 JarOutputStream target = new JarOutputStream(out)) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    byte[] data;
                    if ("game.bin".equals(entry.getName())) {
                        data = game;
                    } else {
                        try (InputStream in = jar.getInputStream(entry)) {
                            data = in.readAllBytes();
                        }
                    }
                    target.putNextEntry(new JarEntry(entry.getName()));
                    target.write(data);
                    target.closeEntry();
                }
            }
            SaveRepository damagedRepo = new SaveRepository(dir);
            Files.move(damaged, damagedRepo.resolve("bad"));
            expectSaveError(() -> damagedRepo.load("bad", null),
                    "校验和不一致必须拒绝读档");
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void slotsAreListedAndValidated() throws Exception {
        Path dir = tempDir();
        try {
            SaveRepository repo = new SaveRepository(dir);
            assert repo.slots().isEmpty();

            RuntimeSession session = RuntimeSession.create(new String[0]);
            session.save(repo, "zeta", null);
            session.save(repo, "alpha", "");
            assert repo.slots().equals(List.of("alpha", "zeta")) : repo.slots();

            expectIllegal(() -> repo.save("../escape", new SavePayload(
                    "resource:world/default-world.xml", "h", 0, List.of()), null));
            expectIllegal(() -> repo.save("含中文", new SavePayload(
                    "resource:world/default-world.xml", "h", 0, List.of()), null));
            assert !Files.exists(dir.resolve("escape.fcsave"));
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void queryAndSaveCommandsStayOutOfReplayLog() throws Exception {
        RuntimeSession session = RuntimeSession.create(new String[0]);
        session.execute("hash");
        session.execute("help");
        session.execute("map");
        session.execute("next 1");
        assert session.commandHistory().equals(List.of("map", "next 1"))
                : session.commandHistory();
    }

    @FcTest
    public void unknownSlotReportsMissingFile() throws Exception {
        Path dir = tempDir();
        try {
            SaveRepository repo = new SaveRepository(dir);
            expectSaveError(() -> repo.load("missing", null), "不存在的槽位应报错");
        } finally {
            deleteTree(dir);
        }
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void expectSaveError(Action action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError(message + "（但操作成功了）");
        } catch (SaveException expected) {
            assert expected.getMessage() != null && !expected.getMessage().isBlank();
        }
    }

    private static void expectIllegal(Action action) throws Exception {
        try {
            action.run();
            throw new AssertionError("非法槽位名应被拒绝");
        } catch (IllegalArgumentException expected) {
            assert expected.getMessage() != null
                    && expected.getMessage().contains("槽位") : expected.getMessage();
        }
    }
}
