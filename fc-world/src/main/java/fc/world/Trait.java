package fc.world;

/**
 * 性格特质（Trait）：影响决策权重（是否勤奋、是否容易焦虑等）。
 */
public enum Trait {
    /** 勤奋：公平天气下倾向工作，疲劳累积慢。 */
    DILIGENT,
    /** 懒散：更早想休息，工作产出略低。 */
    LAZY,
    /** 坚韧：士气下降慢，饥饿影响小。 */
    STOIC,
    /** 焦虑：士气波动大，饥饿时更容易拒绝指令。 */
    ANXIOUS
}
