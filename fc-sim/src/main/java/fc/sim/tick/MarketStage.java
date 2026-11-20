package fc.sim.tick;

import fc.world.WorldState;

/**
 * 阶段 9：市场与财务（供需定价、交易结算、组织收支）。
 *
 * <p><strong>M2 占位</strong>：价格模型与经济结算在 M3 实现。M2 只保留阶段位置，
 * 确保十二阶段顺序固定（价格变化会反过来影响阶段 3/10 的决策，必须晚于生产、早于事件）。</p>
 */
public final class MarketStage implements TickStage {

    private final fc.sim.spi.MarketSystem market;

    public MarketStage() {
        this(null);
    }

    public MarketStage(fc.sim.spi.MarketSystem market) {
        this.market = market;
    }

    @Override
    public String name() {
        return "9-market";
    }

    @Override
    public void apply(WorldState state, TickContext ctx) {
        if (market != null) {
            market.onTick(state, ctx);
        }
    }
}
