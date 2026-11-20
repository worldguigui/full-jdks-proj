package fc.sim.tick;

import fc.world.WorldState;

/**
 * 阶段 3：势力 AI。
 *
 * <p><strong>M2 占位</strong>：势力目标评估、贸易/扩张/外交/冲突决策在 M4 实现
 * （需要 {@code RelationMatrix} 与势力资源，属经济与外交批次）。本阶段保留在管线中，
 * 以保证十二阶段顺序从 M2 起就是固定且可测的。</p>
 */
public final class FactionStage implements TickStage {

    @Override
    public String name() {
        return "3-faction";
    }

    @Override
    public void apply(WorldState state, TickContext ctx) {
        // M4 接管：势力目标评估与决策
    }
}
