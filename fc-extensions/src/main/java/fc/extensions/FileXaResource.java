package fc.extensions;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * 文件类资源的 XA 实现：prepare 阶段把内容写入同目录临时文件并落盘，commit 阶段
 * 原子替换目标文件，rollback 删除临时文件。
 *
 * <p>每个已 prepare 的分支会在目标目录留下 {@code .fcxa-<global>-<branch>.xa}
 * 恢复日志，记录临时文件名；进程重启后同一资源实例可用 {@link #recover(int)}
 * 找回未决分支并继续 commit/rollback。日志与临时文件都只落在目标目录，
 * 不跨目录移动文件，保证 ATOMIC_MOVE 可用。</p>
 *
 * <p>该实现面向本地文件系统，不做网络资源管理；不支持的标志位以
 * {@link XAException#XAER_INVAL}/{@link XAException#XAER_PROTO} 明确拒绝，
 * 不静默忽略。</p>
 */
public final class FileXaResource implements XAResource {

    static final String JOURNAL_PREFIX = ".fcxa-";
    private static final String JOURNAL_SUFFIX = ".xa";

    private final Path target;
    private final byte[] content;
    private Xid active;
    private Xid prepared;
    private int timeoutSeconds;

    /**
     * @param target  最终写入路径（不存在时其父目录会在 prepare 阶段创建）
     * @param content 完整内容；在构造时复制，调用方后续修改不影响事务
     */
    public FileXaResource(Path target, byte[] content) {
        if (target == null || content == null) {
            throw new IllegalArgumentException("目标路径与内容不能为空");
        }
        this.target = target.toAbsolutePath().normalize();
        this.content = content.clone();
    }

    public Path target() {
        return target;
    }

    @Override
    public void start(Xid xid, int flags) throws XAException {
        if (xid == null) {
            throw failure(XAException.XAER_INVAL, null);
        }
        if (flags == TMNOFLAGS) {
            if (active != null) {
                throw failure(XAException.XAER_PROTO, null);
            }
            active = xid;
            prepared = null;
            return;
        }
        if ((flags & (TMJOIN | TMRESUME)) != 0) {
            if (active == null || !active.equals(xid)) {
                throw failure(XAException.XAER_PROTO, null);
            }
            return;
        }
        throw failure(XAException.XAER_INVAL, null);
    }

    @Override
    public void end(Xid xid, int flags) throws XAException {
        requireActive(xid);
        if (flags != TMSUCCESS) {
            throw failure(XAException.XAER_INVAL, null);
        }
    }

    @Override
    public int prepare(Xid xid) throws XAException {
        requireActive(xid);
        if (prepared != null) {
            throw failure(XAException.XAER_PROTO, null);
        }
        try {
            stage(xid);
            prepared = xid;
            active = null;
            return XA_OK;
        } catch (IOException e) {
            discard(xid);
            throw failure(XAException.XAER_RMERR, e);
        }
    }

    @Override
    public void commit(Xid xid, boolean onePhase) throws XAException {
        Journal journal = journalOf(xid);
        if (journal == null) {
            if (!onePhase) {
                throw failure(active != null && active.equals(xid)
                        ? XAException.XAER_PROTO : XAException.XAER_NOTA, null);
            }
            try {
                stage(xid);
            } catch (IOException e) {
                discard(xid);
                throw failure(XAException.XAER_RMERR, e);
            }
            journal = journalOf(xid);
            if (journal == null) {
                throw failure(XAException.XAER_RMERR, null);
            }
        }
        try {
            move(journal.staged, target);
            Files.deleteIfExists(journal.file);
            clear();
        } catch (IOException e) {
            throw failure(XAException.XAER_RMFAIL, e);
        }
    }

    @Override
    public void rollback(Xid xid) throws XAException {
        Journal journal = journalOf(xid);
        if (journal == null) {
            if (active != null && active.equals(xid)) {
                clear();
                return;
            }
            throw failure(XAException.XAER_NOTA, null);
        }
        try {
            Files.deleteIfExists(journal.staged);
            Files.deleteIfExists(journal.file);
            clear();
        } catch (IOException e) {
            throw failure(XAException.XAER_RMFAIL, e);
        }
    }

    @Override
    public void forget(Xid xid) throws XAException {
        Journal journal = journalOf(xid);
        if (journal == null) {
            if (prepared != null && prepared.equals(xid)) {
                clear();
                return;
            }
            throw failure(XAException.XAER_NOTA, null);
        }
        try {
            Files.deleteIfExists(journal.staged);
            Files.deleteIfExists(journal.file);
            clear();
        } catch (IOException e) {
            throw failure(XAException.XAER_RMFAIL, e);
        }
    }

    /** 扫描目标目录中的恢复日志，返回未决分支。 */
    @Override
    public Xid[] recover(int flag) throws XAException {
        Path dir = target.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return new Xid[0];
        }
        List<Xid> xids = new ArrayList<>();
        try (var listing = Files.list(dir)) {
            for (Path file : listing.toList()) {
                String name = file.getFileName().toString();
                if (!name.startsWith(JOURNAL_PREFIX) || !name.endsWith(JOURNAL_SUFFIX)) {
                    continue;
                }
                Xid xid = readXid(file);
                if (xid != null) {
                    xids.add(xid);
                }
            }
        } catch (IOException e) {
            throw failure(XAException.XAER_RMERR, e);
        }
        return xids.toArray(Xid[]::new);
    }

    @Override
    public boolean isSameRM(XAResource other) {
        return other instanceof FileXaResource resource
                && Objects.equals(directoryOf(target), directoryOf(resource.target));
    }

    @Override
    public int getTransactionTimeout() {
        return timeoutSeconds;
    }

    @Override
    public boolean setTransactionTimeout(int seconds) {
        if (seconds < 0) {
            return false;
        }
        timeoutSeconds = seconds;
        return true;
    }

    private void requireActive(Xid xid) throws XAException {
        if (xid == null) {
            throw failure(XAException.XAER_INVAL, null);
        }
        if (active == null) {
            throw failure(XAException.XAER_PROTO, null);
        }
        if (!active.equals(xid)) {
            throw failure(XAException.XAER_NOTA, null);
        }
    }

    /** 写临时文件与恢复日志；只创建父目录，不触碰目标文件。 */
    private void stage(Xid xid) throws IOException {
        Path dir = target.getParent();
        if (dir == null) {
            throw new IOException("目标不能是文件系统根目录：" + target);
        }
        Files.createDirectories(dir);
        Path staged = Files.createTempFile(dir, JOURNAL_PREFIX, ".tmp");
        boolean ok = false;
        try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        try {
            Properties journal = new Properties();
            journal.setProperty("formatId", Integer.toString(xid.getFormatId()));
            journal.setProperty("global", Base64.getEncoder()
                    .encodeToString(xid.getGlobalTransactionId()));
            journal.setProperty("branch", Base64.getEncoder()
                    .encodeToString(xid.getBranchQualifier()));
            journal.setProperty("staged", staged.getFileName().toString());
            Path file = journalFile(dir, xid);
            Path temp = Files.createTempFile(dir, JOURNAL_PREFIX, ".tmp");
            try {
                journal.store(Files.newOutputStream(temp), "Frontier Chronicle XA journal");
                move(temp, file);
            } finally {
                Files.deleteIfExists(temp);
            }
            ok = true;
        } finally {
            if (!ok) {
                Files.deleteIfExists(staged);
            }
        }
    }

    private void discard(Xid xid) {
        Journal journal = journalOf(xid);
        try {
            if (journal != null) {
                Files.deleteIfExists(journal.staged);
                Files.deleteIfExists(journal.file);
            }
        } catch (IOException ignored) {
            // 清理尽力而为；恢复日志仍可让 recover 再次处理。
        }
    }

    private Journal journalOf(Xid xid) {
        if (xid == null) {
            return null;
        }
        Path dir = target.getParent();
        if (dir == null) {
            return null;
        }
        Path file = journalFile(dir, xid);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            Properties props = new Properties();
            try (var in = Files.newInputStream(file)) {
                props.load(in);
            }
            Path staged = dir.resolve(props.getProperty("staged", ""));
            return new Journal(file, staged);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Xid readXid(Path file) {
        try {
            Properties props = new Properties();
            try (var in = Files.newInputStream(file)) {
                props.load(in);
            }
            if (Integer.parseInt(props.getProperty("formatId", "0")) != FcXid.FORMAT_ID) {
                return null;
            }
            byte[] global = Base64.getDecoder().decode(props.getProperty("global", ""));
            byte[] branch = Base64.getDecoder().decode(props.getProperty("branch", ""));
            return new FcXid(global, branch);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Path journalFile(Path dir, Xid xid) {
        String global = java.util.HexFormat.of().formatHex(xid.getGlobalTransactionId());
        String branch = java.util.HexFormat.of().formatHex(xid.getBranchQualifier());
        return dir.resolve(JOURNAL_PREFIX + global + "-" + branch + JOURNAL_SUFFIX);
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path directoryOf(Path path) {
        Path parent = path.toAbsolutePath().normalize().getParent();
        return parent == null ? path : parent;
    }

    private void clear() {
        active = null;
        prepared = null;
    }

    private static XAException failure(int code, Throwable cause) {
        XAException exception = new XAException(code);
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }

    private record Journal(Path file, Path staged) {
    }
}
