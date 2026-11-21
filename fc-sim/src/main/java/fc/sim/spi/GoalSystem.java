package fc.sim.spi;

import fc.sim.tick.TickContext;
import fc.world.WorldState;

/**
 * 阶段目标判定 SPI（实现位于 {@code fc-diplomacy}）。
 *
 * <p>报告阶段调用一次；达成的新目标写入 {@link WorldState#goalsReached()}，
 * 并产生阶段事件。判定只读世界状态，不改变经济账本。</p>
 */
public interface GoalSystem {

    /** 判定 D0–D4，返回本次新达成的目标 id（可能为空）。 */
    java.util.List<String> evaluate(WorldState state, TickContext ctx);
}
