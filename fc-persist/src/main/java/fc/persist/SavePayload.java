package fc.persist;

import java.util.List;

/**
 * 一个存档槽的内容（与运行时解耦，便于 fc-persist 独立测试）。
 *
 * @param worldSource 初始世界来源：{@code resource:<路径>} 或 {@code file:<绝对路径>}
 * @param stateHash   存档时的世界状态 SHA-256，用于读档后逐字节校验
 * @param day         存档时的星历日（仅用于展示/诊断）
 * @param commands    从开局起执行过的全部命令，按原顺序重放
 */
public record SavePayload(String worldSource, String stateHash, long day,
                          List<String> commands) {
    public SavePayload {
        if (worldSource == null || worldSource.isBlank()) {
            throw new IllegalArgumentException("worldSource 不能为空");
        }
        commands = List.copyOf(commands);
    }
}
