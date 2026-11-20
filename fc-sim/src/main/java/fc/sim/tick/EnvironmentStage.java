package fc.sim.tick;

import fc.sim.WorldAssembler;
import fc.sim.spi.ColonySystem;
import fc.world.Ids;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;

/**
 * 阶段 2：环境与生态（资源再生 + 殖民地维护）。
 *
 * <p>各产地的每日产出计入地点库存与权威账本；环境强度过高时产出减半（灾害性天气）；
 * 被风暴中断生产的地点当日没有自然产出。产出是世界新增资源的合法来源之一，
 * 因此账本与地点视图同步增加。</p>
 */
public final class EnvironmentStage implements TickStage {

    private final ColonySystem colonies;

    public EnvironmentStage() {
        this(null);
    }

    public EnvironmentStage(ColonySystem colonies) {
        this.colonies = colonies;
    }

    @Override
    public String name() {
        return "2-environment";
    }

    @Override
    public void apply(WorldState state, TickContext ctx) {
        boolean harsh = ctx.weather() >= 85;
        Map<Ids.LocationId, WorldAssembler.Production> ordered =
                new TreeMap<>(ctx.production());
        for (Map.Entry<Ids.LocationId, WorldAssembler.Production> e : ordered.entrySet()) {
            if (state.graph().get(e.getKey()) == null) {
                continue;
            }
            if (state.productionDisrupted(e.getKey())) {
                ctx.report("生产中断：" + nameOf(state, e.getKey()));
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
            state.produce(e.getKey(), p.resource(), amount, "自然产出");
        }
        if (colonies != null) {
            colonies.onTick(state, ctx);
        }
    }

    private static String nameOf(WorldState state, Ids.LocationId id) {
        var loc = state.graph().get(id);
        return loc == null ? ("#" + id.id()) : loc.name();
    }
}
