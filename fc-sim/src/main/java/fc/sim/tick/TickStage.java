package fc.sim.tick;

import fc.world.WorldState;

/**
 * Tick 阶段：每个阶段在世界状态上执行一个确定性步骤。
 *
 * <p>十二阶段的固定顺序见 {@link TickPipeline}（依据 game-design §8.2 的依赖 DAG）。</p>
 */
public interface TickStage {

    /** 阶段名（用于日志与诊断）。 */
    String name();

    /** 在世界状态上执行本阶段。 */
    void apply(WorldState state, TickContext ctx);
}
