package fc.sim.tick;

import fc.sim.spi.FactionSystem;
import fc.world.WorldState;

/**
 * 阶段 3：势力 AI。
 *
 * <p>势力按优先货物与库存目标行动（采购、储备、外交姿态）。具体策略由
 * {@code fc-diplomacy.FactionSystemImpl} 实现，经 SPI 注入，避免领域模块互相依赖。</p>
 */
public final class FactionStage implements TickStage {

    private final FactionSystem factions;

    public FactionStage() {
        this(null);
    }

    public FactionStage(FactionSystem factions) {
        this.factions = factions;
    }

    @Override
    public String name() {
        return "3-faction";
    }

    @Override
    public void apply(WorldState state, TickContext ctx) {
        if (factions != null) {
            factions.onTick(state, ctx);
        }
    }
}
