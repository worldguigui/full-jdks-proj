package fc.extensions;

import javax.transaction.xa.XAException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 本地文件的两阶段提交协调器：把多份内容作为同一事务的多个分支，
 * 全部 prepare 成功后才逐个 commit；任一 prepare 失败则全部 rollback，
 * 不留下「只写了一半」的导出。
 *
 * <p>每个 {@link Artifact} 对应一个 {@link FileXaResource} 分支；分支使用同一
 * 全局事务 ID、不同分支号。协调器只依赖 {@code javax.transaction.xa} 的
 * {@link javax.transaction.xa.XAResource} 协议，不感知文件格式。提交阶段的失败
 * 会报告为启发式混合（{@link XAException#XA_HEURMIX}），因为已提交的分支无法回滚。</p>
 */
public final class TwoPhaseFiles {

    private TwoPhaseFiles() {
    }

    /** 一份待提交内容；构造时复制字节数组。 */
    public record Artifact(Path target, byte[] content) {
        public Artifact {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(content, "content");
            target = target.toAbsolutePath().normalize();
            content = content.clone();
        }
    }

    /**
     * 原子地提交全部文件。
     *
     * @return 已提交的目标路径（顺序与入参一致）
     * @throws IOException 任一阶段失败；prepare 失败时所有分支已回滚
     */
    public static List<Path> commit(Artifact... artifacts) throws IOException {
        if (artifacts == null || artifacts.length == 0) {
            throw new IllegalArgumentException("至少需要一个文件");
        }
        Set<Path> seen = new HashSet<>();
        for (Artifact artifact : artifacts) {
            if (!seen.add(artifact.target())) {
                throw new IllegalArgumentException("同一事务中目标重复：" + artifact.target());
            }
        }
        FcXid global = FcXid.random();
        List<FileXaResource> resources = new ArrayList<>();
        List<FcXid> branches = new ArrayList<>();
        List<Path> targets = new ArrayList<>();
        for (int i = 0; i < artifacts.length; i++) {
            Artifact artifact = artifacts[i];
            resources.add(new FileXaResource(artifact.target(), artifact.content()));
            branches.add(global.branch(i + 1));
            targets.add(artifact.target());
        }

        int phase = 0; // 0=start，1=prepare 之后
        try {
            for (int i = 0; i < resources.size(); i++) {
                FileXaResource resource = resources.get(i);
                FcXid xid = branches.get(i);
                resource.start(xid, javax.transaction.xa.XAResource.TMNOFLAGS);
                resource.end(xid, javax.transaction.xa.XAResource.TMSUCCESS);
            }
            for (int i = 0; i < resources.size(); i++) {
                resources.get(i).prepare(branches.get(i));
            }
            phase = 1;
            for (int i = 0; i < resources.size(); i++) {
                resources.get(i).commit(branches.get(i), false);
            }
            return List.copyOf(targets);
        } catch (XAException e) {
            for (int i = 0; i < resources.size(); i++) {
                try {
                    resources.get(i).rollback(branches.get(i));
                } catch (XAException ignored) {
                    // 已经提交或从未开始的分支没有可回滚状态。
                }
            }
            if (phase == 1) {
                XAException heuristic = new XAException(XAException.XA_HEURMIX);
                heuristic.initCause(e);
                throw new IOException("两阶段提交在 commit 阶段失败，可能处于启发式混合状态"
                        + "（部分文件已提交）：XA 错误码 " + e.errorCode, heuristic);
            }
            throw new IOException("两阶段提交在 prepare 阶段失败，已全部回滚：XA 错误码 "
                    + e.errorCode, e);
        }
    }

    /** 便捷方法：把同一份内容原子写入多个目标。 */
    public static List<Path> mirror(Path first, Path second, byte[] content) throws IOException {
        return commit(new Artifact(first, content), new Artifact(second, content));
    }

    /** 目标目录下是否存在未决 XA 日志（测试与诊断用）。 */
    public static boolean hasPendingJournal(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var listing = Files.list(directory)) {
            return listing.anyMatch(path -> path.getFileName().toString()
                    .startsWith(FileXaResource.JOURNAL_PREFIX));
        }
    }
}
