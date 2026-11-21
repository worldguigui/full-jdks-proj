package fc.extensions;

import fc.testkit.FcTest;
import fc.testkit.TestRunner;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** XA 文件事务：双写提交、prepare 失败回滚、恢复日志、协议错误与同 RM 判定。 */
public final class TwoPhaseFilesTest {

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(TwoPhaseFilesTest.class))) {
            System.exit(1);
        }
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @FcTest
    public void commitsBothTargetsAndLeavesNoJournal() throws Exception {
        Path dir = Files.createTempDirectory("fc-xa-");
        try {
            Path first = dir.resolve("a.csv");
            Path second = dir.resolve("sub/b.csv");
            List<Path> written = TwoPhaseFiles.commit(
                    new TwoPhaseFiles.Artifact(first, utf8("甲")),
                    new TwoPhaseFiles.Artifact(second, utf8("乙")));
            assert written.equals(List.of(first.toAbsolutePath().normalize(),
                    second.toAbsolutePath().normalize())) : written;
            assert Files.readString(first).equals("甲");
            assert Files.readString(second).equals("乙");
            assert !TwoPhaseFiles.hasPendingJournal(dir);
            assert !TwoPhaseFiles.hasPendingJournal(dir.resolve("sub"));

            Path third = dir.resolve("c.txt");
            Path fourth = dir.resolve("d.txt");
            List<Path> mirrored = TwoPhaseFiles.mirror(third, fourth, utf8("同"));
            assert mirrored.size() == 2;
            assert Files.readString(third).equals("同");
            assert Files.readString(fourth).equals("同");

            try (var listing = Files.list(dir)) {
                assert listing.noneMatch(p -> p.getFileName().toString()
                        .startsWith(FileXaResource.JOURNAL_PREFIX)) : "不得残留 XA 日志";
            }
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void prepareFailureRollsBackAllBranches() throws Exception {
        Path dir = Files.createTempDirectory("fc-xa-fail-");
        try {
            Path first = dir.resolve("a.txt");
            Path blocker = Files.writeString(dir.resolve("blocker"), "not a directory");
            Path second = blocker.resolve("b.txt");
            boolean failed = false;
            try {
                TwoPhaseFiles.commit(new TwoPhaseFiles.Artifact(first, utf8("甲")),
                        new TwoPhaseFiles.Artifact(second, utf8("乙")));
            } catch (IOException e) {
                failed = true;
                assert e.getMessage().contains("prepare") : e.getMessage();
            }
            assert failed : "第二个分支无法 prepare 时必须失败";
            assert !Files.exists(first) : "prepare 失败不得留下第一个目标";
            assert !TwoPhaseFiles.hasPendingJournal(dir) : "失败后不得残留 XA 日志";
            assert Files.readString(blocker).equals("not a directory");
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void recoversPreparedBranchAfterRestart() throws Exception {
        Path dir = Files.createTempDirectory("fc-xa-recover-");
        try {
            Path target = dir.resolve("recovered.txt");
            FcXid xid = FcXid.random();
            FileXaResource resource = new FileXaResource(target, utf8("恢复内容"));
            resource.start(xid, XAResource.TMNOFLAGS);
            resource.end(xid, XAResource.TMSUCCESS);
            assert resource.prepare(xid) == XAResource.XA_OK;
            assert !Files.exists(target) : "prepare 阶段不得发布目标文件";
            assert TwoPhaseFiles.hasPendingJournal(dir);

            FileXaResource restarted = new FileXaResource(target, new byte[0]);
            Xid[] pending = restarted.recover(XAResource.TMSTARTRSCAN);
            assert pending.length == 1 : pending.length;
            assert pending[0].equals(xid) : pending[0];
            restarted.commit(pending[0], false);

            assert Files.readString(target).equals("恢复内容");
            assert !TwoPhaseFiles.hasPendingJournal(dir);
            assert restarted.recover(XAResource.TMENDRSCAN).length == 0;
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void rollbackCleansPreparedStateAndProtocolIsChecked() throws Exception {
        Path dir = Files.createTempDirectory("fc-xa-rollback-");
        try {
            Path target = dir.resolve("rolled.txt");
            FileXaResource resource = new FileXaResource(target, utf8("不要"));
            FcXid xid = FcXid.random();

            boolean endWithoutStart = false;
            try {
                resource.end(xid, XAResource.TMSUCCESS);
            } catch (XAException e) {
                endWithoutStart = e.errorCode == XAException.XAER_PROTO;
            }
            assert endWithoutStart : "未 start 就 end 必须是协议错误";

            resource.start(xid, XAResource.TMNOFLAGS);
            resource.end(xid, XAResource.TMSUCCESS);
            resource.prepare(xid);
            resource.rollback(xid);
            assert !Files.exists(target);
            assert !TwoPhaseFiles.hasPendingJournal(dir);
            assert resource.recover(XAResource.TMSTARTRSCAN).length == 0;

            boolean rollbackTwice = false;
            try {
                resource.rollback(xid);
            } catch (XAException e) {
                rollbackTwice = e.errorCode == XAException.XAER_NOTA;
            }
            assert rollbackTwice : "重复 rollback 必须是 NOTA";

            boolean unknownCommit = false;
            try {
                resource.commit(FcXid.random(), false);
            } catch (XAException e) {
                unknownCommit = e.errorCode == XAException.XAER_NOTA;
            }
            assert unknownCommit : "未知分支 commit 必须是 NOTA";

            assert resource.setTransactionTimeout(5);
            assert resource.getTransactionTimeout() == 5;
            assert !resource.setTransactionTimeout(-1);

            Path otherDir = Files.createTempDirectory("fc-xa-other-");
            try {
                assert resource.isSameRM(new FileXaResource(
                        dir.resolve("sibling.txt"), utf8("x"))) : "同目录应视为同一 RM";
                assert !resource.isSameRM(new FileXaResource(
                        otherDir.resolve("x.txt"), utf8("x"))) : "不同目录不是同一 RM";
            } finally {
                deleteTree(otherDir);
            }
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void onePhaseCommitWritesDirectlyAndRejectsEmptyOrDuplicateTargets() throws Exception {
        Path dir = Files.createTempDirectory("fc-xa-one-phase-");
        try {
            Path target = dir.resolve("one.txt");
            FileXaResource resource = new FileXaResource(target, utf8("一次"));
            FcXid xid = FcXid.random();
            resource.start(xid, XAResource.TMNOFLAGS);
            resource.end(xid, XAResource.TMSUCCESS);
            resource.commit(xid, true);
            assert Files.readString(target).equals("一次");
            assert !TwoPhaseFiles.hasPendingJournal(dir);

            Path same = dir.resolve("dup.txt");
            boolean duplicate = false;
            try {
                TwoPhaseFiles.commit(new TwoPhaseFiles.Artifact(same, utf8("a")),
                        new TwoPhaseFiles.Artifact(same, utf8("b")));
            } catch (IllegalArgumentException e) {
                duplicate = true;
            }
            assert duplicate : "同一事务重复目标必须拒绝";

            boolean empty = false;
            try {
                TwoPhaseFiles.commit();
            } catch (IllegalArgumentException e) {
                empty = true;
            }
            assert empty;
        } finally {
            deleteTree(dir);
        }
    }
}
