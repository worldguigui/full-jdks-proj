package fc.sim.tick;

import fc.sim.WorldAssembler;
import fc.world.Ids;
import fc.world.Location;
import fc.world.LocationOps;
import fc.world.Stock;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 阶段 2：环境与生态（资源再生）。
 *
 * <p>各产地的每日产出计入地点库存与权威账本；环境强度过高时产出减半（灾害性天气）。
 * 守恒说明：产出是世界新增资源的合法来源之一，因此账本与分区视图同步增加。</p>
 */
public final class EnvironmentStage implements TickStage {

    @Override
    public String name() {
        return "2-environment";
    }

    @Override
    public void apply(WorldState state, TickContext ctx) {
        boolean harsh = ctx.weather() >= 85;
        for (Map.Entry<Ids.LocationId, WorldAssembler.Production> e : ctx.production().entrySet()) {
            Location loc = state.graph().get(e.getKey());
            if (loc == null) {
                continue;
            }
            WorldAssembler.Production p = e.getValue();
            if (p.perDay().signum() == 0) {
                continue;
            }
            BigDecimal amount = harsh ? p.perDay().divide(BigDecimal.TWO) : p.perDay();
            if (amount.signum() == 0) {
                continue;
            }
            Stock next = loc.stock().add(p.resource(), amount);
            state.graph().replace(LocationOps.withStock(loc, next));
            state.ledger().credit(p.resource(), amount);
        }
    }
}
