package fc.extensions;

import javax.transaction.xa.Xid;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 本地 XA 分支标识：格式号固定为 {@code 0x4643}（"FC"），全局事务用 16 字节
 * 随机数，分支号按资源序号生成。
 *
 * <p>XA 规范只要求 {@link Xid} 在资源管理器内可比较；这里同时实现稳定的
 * {@code equals}/{@code hashCode}，便于事务管理器与恢复日志核对同一分支。</p>
 */
public final class FcXid implements Xid {

    /** {@code 0x4643} = ASCII "FC"，标识 Frontier Chronicle 的本地事务域。 */
    public static final int FORMAT_ID = 0x4643;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] global;
    private final byte[] branch;

    public FcXid(byte[] global, byte[] branch) {
        if (global == null || global.length == 0 || global.length > MAXGTRIDSIZE) {
            throw new IllegalArgumentException("全局事务 ID 长度必须在 1–" + MAXGTRIDSIZE + " 字节");
        }
        if (branch == null || branch.length == 0 || branch.length > MAXBQUALSIZE) {
            throw new IllegalArgumentException("分支限定符长度必须在 1–" + MAXBQUALSIZE + " 字节");
        }
        this.global = global.clone();
        this.branch = branch.clone();
    }

    /** 新建一个全局事务：16 字节随机数，分支号从 1 开始。 */
    public static FcXid random() {
        byte[] global = new byte[16];
        RANDOM.nextBytes(global);
        return new FcXid(global, new byte[] {1});
    }

    /** 同一全局事务下的下一分支。 */
    public FcXid branch(int index) {
        if (index < 1 || index > 255) {
            throw new IllegalArgumentException("分支号必须在 1–255 之间：" + index);
        }
        return new FcXid(global, new byte[] {(byte) index});
    }

    @Override
    public int getFormatId() {
        return FORMAT_ID;
    }

    @Override
    public byte[] getGlobalTransactionId() {
        return global.clone();
    }

    @Override
    public byte[] getBranchQualifier() {
        return branch.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Xid xid
                && xid.getFormatId() == FORMAT_ID
                && Arrays.equals(global, xid.getGlobalTransactionId())
                && Arrays.equals(branch, xid.getBranchQualifier());
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(global) + Arrays.hashCode(branch);
    }

    @Override
    public String toString() {
        return "FcXid[global=" + java.util.HexFormat.of().formatHex(global)
                + ", branch=" + java.util.HexFormat.of().formatHex(branch) + "]";
    }
}
