package fc.history.vault;

import fc.history.query.ChronicleExport;
import fc.history.query.ChronicleQuery;
import fc.world.Chronicle;
import fc.world.GameEvent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

/**
 * 档案库：把编年史写成可校验的 ZIP 快照，并用索引、文件锁与变更监视维护镜像。
 *
 * <p>一个档案库目录包含：</p>
 *
 * <ul>
 *   <li>{@code chronicle-<日>-<摘要前 12 位>.zip}：快照本体，内容即
 *       {@link ChronicleExport#toZip} 的证据包；</li>
 *   <li>{@code index.tsv}：逐行 {@code 文件 / 日 / 字节数 / SHA-256 / 事件数 / CRC32}，
 *       读取时用内存映射与 CRC32 校验；</li>
 *   <li>{@code .vault.lock}：跨进程排他锁，同一时刻只允许一个写入者。</li>
 * </ul>
 *
 * <p>快照按内容寻址：同样的编年史重复写入不会产生第二份文件，也不会重复记索引。
 * 写入路径是「进程内临时 ZIP → {@link AsynchronousFileChannel} 异步落盘 → force →
 * 追加索引」，失败不会留下半个索引行。索引读取、摘要计算与变更监视分别使用
 * {@link MappedByteBuffer}、直接 {@link ByteBuffer} 与 {@link WatchService}。</p>
 */
public final class ChronicleVault {

    /** 索引文件名。 */
    public static final String INDEX_FILE = "index.tsv";
    /** 排他锁文件名。 */
    public static final String LOCK_FILE = ".vault.lock";
    /** 快照文件名前缀。 */
    public static final String SNAPSHOT_PREFIX = "chronicle-";

    private static final int DIGEST_PREFIX = 12;
    private static final int INDEX_FIELDS = 6;

    private ChronicleVault() {
    }

    /** 一份快照的索引记录。 */
    public record Snapshot(String file, long day, long bytes, String sha256, int events) {

        public Snapshot {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(sha256, "sha256");
        }

        /** 相对档案库根目录的绝对路径。 */
        public Path path(Path root) {
            return root.resolve(file);
        }
    }

    /** 单份快照的校验结果。 */
    public record Check(String file, boolean ok, String detail) {
    }

    /** 档案库错误：锁被占用、索引损坏、快照缺失或内容不一致。 */
    public static final class VaultException extends IOException {
        private static final long serialVersionUID = 1L;

        public VaultException(String message) {
            super(message);
        }

        public VaultException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 写入一份编年史快照并追加索引；同一内容重复写入是幂等的。
     *
     * @return 新写入或已存在的快照记录
     * @throws VaultException 档案库被其他写入者占用、索引损坏或同名文件内容不一致
     */
    public static Snapshot write(Chronicle chronicle, Path root) throws IOException {
        if (chronicle == null) {
            throw new IllegalArgumentException("chronicle 不能为空");
        }
        Path dir = prepare(root);
        try (FileChannel lockChannel = FileChannel.open(dir.resolve(LOCK_FILE),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = acquire(lockChannel)) {
            Path temp = Files.createTempFile(dir, ".snapshot-", ".zip");
            try {
                ChronicleExport.toZip(chronicle, temp, ChronicleQuery.all());
                byte[] payload = Files.readAllBytes(temp);
                String digest = sha256(payload);
                List<GameEvent> events = chronicle.all();
                long day = events.isEmpty() ? 0 : events.get(events.size() - 1).when().day();
                String name = SNAPSHOT_PREFIX + "%04d-%s.zip".formatted(
                        day, digest.substring(0, DIGEST_PREFIX));
                Path target = dir.resolve(name);
                Snapshot snapshot = new Snapshot(name, day, payload.length, digest,
                        chronicle.size());

                boolean known = list(dir).stream()
                        .anyMatch(entry -> entry.file().equals(name));
                if (Files.exists(target)) {
                    if (!sha256(target).equals(digest)) {
                        throw new VaultException("同名快照内容不一致：" + name);
                    }
                } else {
                    writeAsync(target, payload);
                }
                if (!known) {
                    appendIndex(dir, snapshot);
                }
                return snapshot;
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    /** 读取索引（内存映射 + 逐行 CRC32 校验）；索引不存在时返回空列表。 */
    public static List<Snapshot> list(Path root) throws IOException {
        Path dir = prepare(root);
        Path index = dir.resolve(INDEX_FILE);
        if (!Files.exists(index)) {
            return List.of();
        }
        byte[] data;
        try (FileChannel channel = FileChannel.open(index, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size == 0) {
                return List.of();
            }
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
            data = new byte[mapped.remaining()];
            mapped.get(data);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            throw new VaultException("索引不是合法 UTF-8：" + index, e);
        }
        List<Snapshot> snapshots = new ArrayList<>();
        int lineNumber = 0;
        for (String line : text.split("\n")) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            snapshots.add(parseLine(line, lineNumber));
        }
        return List.copyOf(snapshots);
    }

    /** 逐份重算摘要与字节数，返回校验结果；索引损坏时抛出 {@link VaultException}。 */
    public static List<Check> verify(Path root) throws IOException {
        Path dir = prepare(root);
        List<Check> checks = new ArrayList<>();
        for (Snapshot snapshot : list(dir)) {
            Path file = snapshot.path(dir);
            if (!Files.isRegularFile(file)) {
                checks.add(new Check(snapshot.file(), false, "快照缺失"));
                continue;
            }
            BasicFileAttributes attributes =
                    Files.readAttributes(file, BasicFileAttributes.class);
            String digest = sha256(file);
            if (attributes.size() != snapshot.bytes()) {
                checks.add(new Check(snapshot.file(), false,
                        "字节数不一致（索引 " + snapshot.bytes() + "，实际 "
                                + attributes.size() + "）"));
            } else if (!digest.equalsIgnoreCase(snapshot.sha256())) {
                checks.add(new Check(snapshot.file(), false, "SHA-256 不一致"));
            } else {
                checks.add(new Check(snapshot.file(), true,
                        "SIZE+SHA-256 通过（" + snapshot.events() + " 事件）"));
            }
        }
        return List.copyOf(checks);
    }

    /**
     * 监视档案库目录，等待新快照出现（忽略索引、锁与临时文件）。
     *
     * @param timeout 最长等待时间；超时返回空列表
     * @return 观察到的新快照文件名（按事件顺序）
     */
    public static List<String> watch(Path root, Duration timeout) throws IOException {
        Path dir = prepare(root);
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 不能为负数");
        }
        long budget = timeout.toNanos();
        try (WatchService service = FileSystems.getDefault().newWatchService()) {
            dir.register(service, StandardWatchEventKinds.ENTRY_CREATE);
            long deadline = System.nanoTime() + budget;
            List<String> created = new ArrayList<>();
            while (created.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                WatchKey key;
                if (remaining <= 0) {
                    key = service.poll();
                } else {
                    key = service.poll(remaining, TimeUnit.NANOSECONDS);
                }
                if (key == null) {
                    break;
                }
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    String name = ((Path) event.context()).getFileName().toString();
                    if (name.equals(INDEX_FILE) || name.equals(LOCK_FILE)
                            || name.startsWith(".snapshot-")) {
                        continue;
                    }
                    created.add(name);
                }
                key.reset();
            }
            return List.copyOf(created);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VaultException("档案库监视被中断", e);
        }
    }

    // ------------------------------------------------------------ 内部实现

    private static Path prepare(Path root) throws IOException {
        if (root == null) {
            throw new IllegalArgumentException("档案库目录不能为空");
        }
        Path dir = root.toAbsolutePath().normalize();
        Files.createDirectories(dir);
        return dir;
    }

    private static FileLock acquire(FileChannel channel) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new VaultException("档案库正被其他进程写入");
            }
            return lock;
        } catch (OverlappingFileLockException e) {
            throw new VaultException("档案库正被当前进程的另一个写入者占用", e);
        }
    }

    private static void appendIndex(Path dir, Snapshot snapshot) throws IOException {
        String fields = String.join("\t", snapshot.file(),
                Long.toString(snapshot.day()),
                Long.toString(snapshot.bytes()),
                snapshot.sha256(),
                Integer.toString(snapshot.events()));
        byte[] line = (fields + "\t" + crc32(fields) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        try (FileChannel index = FileChannel.open(dir.resolve(INDEX_FILE),
                StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
             FileLock ignored = index.lock()) {
            ByteBuffer buffer = ByteBuffer.wrap(line);
            long position = index.size();
            while (buffer.hasRemaining()) {
                position += index.write(buffer, position);
            }
            index.force(true);
        }
    }

    private static Snapshot parseLine(String line, int lineNumber) throws IOException {
        String[] parts = line.split("\t", -1);
        if (parts.length != INDEX_FIELDS) {
            throw new VaultException("索引第 " + lineNumber + " 行字段数应为 "
                    + INDEX_FIELDS + "，实际 " + parts.length);
        }
        String fields = String.join("\t", parts[0], parts[1], parts[2], parts[3], parts[4]);
        if (!crc32(fields).equalsIgnoreCase(parts[5])) {
            throw new VaultException("索引第 " + lineNumber + " 行 CRC32 校验失败");
        }
        try {
            return new Snapshot(parts[0], Long.parseLong(parts[1]),
                    Long.parseLong(parts[2]), parts[3], Integer.parseInt(parts[4]));
        } catch (NumberFormatException e) {
            throw new VaultException("索引第 " + lineNumber + " 行数值非法", e);
        }
    }

    private static String crc32(String value) {
        CRC32 crc = new CRC32();
        crc.update(value.getBytes(StandardCharsets.UTF_8));
        return Long.toHexString(crc.getValue());
    }

    private static String sha256(byte[] payload) {
        return HexFormat.of().formatHex(digest().digest(payload));
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(8192);
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    private static void writeAsync(Path target, byte[] payload) throws IOException {
        try (AsynchronousFileChannel channel = AsynchronousFileChannel.open(target,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            writeFully(channel, ByteBuffer.wrap(payload), 0, done, failure);
            try {
                if (!done.await(30, TimeUnit.SECONDS)) {
                    throw new VaultException("异步写入超时：" + target);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new VaultException("异步写入被中断：" + target, e);
            }
            Throwable error = failure.get();
            if (error instanceof IOException io) {
                throw io;
            }
            if (error != null) {
                throw new VaultException("异步写入失败：" + target, error);
            }
            try (FileChannel force = FileChannel.open(target, StandardOpenOption.WRITE)) {
                force.force(true);
            }
        }
    }

    private static void writeFully(AsynchronousFileChannel channel, ByteBuffer buffer,
                                   long position, CountDownLatch done,
                                   AtomicReference<Throwable> failure) {
        channel.write(buffer, position, null, new CompletionHandler<Integer, Object>() {
            @Override
            public void completed(Integer written, Object attachment) {
                try {
                    if (written == null || written <= 0) {
                        throw new VaultException("异步写入未推进");
                    }
                    if (buffer.hasRemaining()) {
                        writeFully(channel, buffer, position + written, done, failure);
                    } else {
                        done.countDown();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    done.countDown();
                }
            }

            @Override
            public void failed(Throwable exc, Object attachment) {
                failure.compareAndSet(null, exc);
                done.countDown();
            }
        });
    }
}
