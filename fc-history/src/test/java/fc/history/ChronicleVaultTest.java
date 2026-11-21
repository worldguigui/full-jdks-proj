package fc.history;

import fc.history.vault.ChronicleVault;
import fc.testkit.FcTest;
import fc.testkit.TestRunner;
import fc.world.GameEvent;
import fc.world.Ids.AgentId;
import fc.world.Ids.LocationId;
import fc.world.time.Stardate;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/** 档案库：内容寻址快照、内存映射索引、CRC 校验、排他锁与变更监视。 */
public final class ChronicleVaultTest {

    private static final LocationId L1 = new LocationId(1);
    private static final AgentId A7 = new AgentId(7);

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(ChronicleVaultTest.class))) {
            System.exit(1);
        }
    }

    private static InMemoryChronicle chronicle(int events) {
        InMemoryChronicle chronicle = new InMemoryChronicle();
        for (int i = 0; i < events; i++) {
            chronicle.record(new GameEvent.CrewAte(new Stardate(1 + i / 2), L1, A7,
                    40 + i));
        }
        return chronicle;
    }

    private static Path tempDir() throws Exception {
        return Files.createTempDirectory("fc-vault-test-");
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
    public void writeCreatesSnapshotAndIndex() throws Exception {
        Path dir = tempDir();
        try {
            ChronicleVault.Snapshot snapshot = ChronicleVault.write(chronicle(4), dir);
            assert snapshot.file().startsWith(ChronicleVault.SNAPSHOT_PREFIX) : snapshot;
            assert snapshot.file().endsWith(".zip") : snapshot.file();
            assert snapshot.day() == 2 : snapshot.day();
            assert snapshot.events() == 4 : snapshot.events();
            assert snapshot.sha256().length() == 64 : snapshot.sha256();
            Path zip = snapshot.path(dir);
            assert Files.isRegularFile(zip);
            assert Files.size(zip) == snapshot.bytes() : Files.size(zip);
            assert Files.isRegularFile(dir.resolve(ChronicleVault.INDEX_FILE));
            assert Files.isRegularFile(dir.resolve(ChronicleVault.LOCK_FILE));

            try (ZipFile file = new ZipFile(zip.toFile())) {
                assert file.getEntry("chronicle.xml") != null;
                assert file.getEntry("chronicle.csv") != null;
                assert file.getEntry("chronicle.html") != null;
                assert file.getEntry("manifest.properties") != null;
            }

            List<ChronicleVault.Snapshot> listed = ChronicleVault.list(dir);
            assert listed.size() == 1 : listed;
            assert listed.get(0).equals(snapshot) : listed;
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void writeIsIdempotentAndIndexKeepsHistory() throws Exception {
        Path dir = tempDir();
        try {
            InMemoryChronicle chronicle = chronicle(2);
            ChronicleVault.Snapshot first = ChronicleVault.write(chronicle, dir);
            ChronicleVault.Snapshot again = ChronicleVault.write(chronicle, dir);
            assert first.equals(again) : "同内容应复用同一快照";
            assert ChronicleVault.list(dir).size() == 1 : ChronicleVault.list(dir);

            chronicle.record(new GameEvent.IonStorm(new Stardate(9), L1, 2));
            ChronicleVault.Snapshot second = ChronicleVault.write(chronicle, dir);
            assert !second.file().equals(first.file());
            List<ChronicleVault.Snapshot> listed = ChronicleVault.list(dir);
            assert listed.size() == 2 : listed;
            assert listed.get(0).file().equals(first.file()) : listed;
            assert listed.get(1).file().equals(second.file()) : listed;
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void verifyDetectsTamperingAndMissingSnapshots() throws Exception {
        Path dir = tempDir();
        try {
            InMemoryChronicle chronicle = chronicle(2);
            ChronicleVault.Snapshot healthy = ChronicleVault.write(chronicle, dir);
            chronicle.record(new GameEvent.IonStorm(new Stardate(9), L1, 1));
            ChronicleVault.Snapshot tampered = ChronicleVault.write(chronicle, dir);

            byte[] bytes = Files.readAllBytes(tampered.path(dir));
            bytes[bytes.length / 2] ^= 0x5A;
            Files.write(tampered.path(dir), bytes);

            List<ChronicleVault.Check> checks = ChronicleVault.verify(dir);
            assert checks.size() == 2 : checks;
            ChronicleVault.Check ok = checks.stream()
                    .filter(c -> c.file().equals(healthy.file())).findFirst().orElseThrow();
            ChronicleVault.Check bad = checks.stream()
                    .filter(c -> c.file().equals(tampered.file())).findFirst().orElseThrow();
            assert ok.ok() : ok;
            assert !bad.ok() : bad;
            assert bad.detail().contains("SHA-256") : bad;

            Files.delete(tampered.path(dir));
            ChronicleVault.Check missing = ChronicleVault.verify(dir).stream()
                    .filter(c -> c.file().equals(tampered.file())).findFirst().orElseThrow();
            assert !missing.ok() : missing;
            assert missing.detail().contains("缺失") : missing;
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void concurrentWriterIsRejectedByFileLock() throws Exception {
        Path dir = tempDir();
        try {
            ChronicleVault.write(chronicle(1), dir);
            boolean rejected = false;
            try (FileChannel channel = FileChannel.open(dir.resolve(ChronicleVault.LOCK_FILE),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                assert lock.isValid();
                try {
                    ChronicleVault.write(chronicle(2), dir);
                } catch (ChronicleVault.VaultException e) {
                    rejected = true;
                    assert e.getMessage().contains("占用") : e.getMessage();
                }
            }
            assert rejected : "锁被占用时必须拒绝写入";
            ChronicleVault.write(chronicle(3), dir);
            assert ChronicleVault.list(dir).stream()
                    .anyMatch(s -> s.events() == 3) : ChronicleVault.list(dir);
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void watchObservesNewSnapshot() throws Exception {
        Path dir = tempDir();
        try {
            CompletableFuture<List<String>> observed = new CompletableFuture<>();
            Thread watcher = Thread.ofPlatform().daemon().start(() -> {
                try {
                    observed.complete(ChronicleVault.watch(dir, Duration.ofSeconds(15)));
                } catch (Throwable t) {
                    observed.completeExceptionally(t);
                }
            });
            Thread.sleep(500);
            InMemoryChronicle chronicle = chronicle(1);
            String expected = null;
            for (int i = 0; i < 10 && !observed.isDone(); i++) {
                chronicle.record(new GameEvent.IonStorm(new Stardate(5 + i), L1, i));
                expected = ChronicleVault.write(chronicle, dir).file();
                Thread.sleep(200);
            }
            List<String> names = observed.get(20, TimeUnit.SECONDS);
            watcher.join(2000);
            assert !names.isEmpty() : "应观察到新快照";
            assert names.stream().allMatch(n -> n.endsWith(".zip")) : names;
            assert expected != null : "写入应发生";
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void invalidInputsAndCorruptIndexAreRejected() throws Exception {
        Path dir = tempDir();
        try {
            assert ChronicleVault.list(dir).isEmpty();
            boolean nullChronicle = false;
            try {
                ChronicleVault.write(null, dir);
            } catch (IllegalArgumentException e) {
                nullChronicle = true;
            }
            assert nullChronicle;
            boolean nullRoot = false;
            try {
                ChronicleVault.list(null);
            } catch (IllegalArgumentException e) {
                nullRoot = true;
            }
            assert nullRoot;
            boolean negativeTimeout = false;
            try {
                ChronicleVault.watch(dir, Duration.ofSeconds(-1));
            } catch (IllegalArgumentException e) {
                negativeTimeout = true;
            }
            assert negativeTimeout;

            ChronicleVault.write(chronicle(1), dir);
            Files.writeString(dir.resolve(ChronicleVault.INDEX_FILE),
                    "broken-line\n", StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
            boolean corrupt = false;
            try {
                ChronicleVault.list(dir);
            } catch (ChronicleVault.VaultException e) {
                corrupt = true;
                assert e.getMessage().contains("索引") : e.getMessage();
            }
            assert corrupt : "损坏索引必须被拒绝";
        } finally {
            deleteTree(dir);
        }
    }
}
