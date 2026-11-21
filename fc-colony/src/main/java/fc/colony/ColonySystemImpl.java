package fc.colony;

import fc.sim.spi.ColonySystem;
import fc.sim.tick.TickContext;
import fc.world.Agent;
import fc.world.ColonyState;
import fc.world.ResourceKind;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 殖民地系统（阶段 2）：生活物资维护与人口增长。
 *
 * <p>等级 ≥ 1 的殖民地每天按人口消耗口粮与饮水；物资不足时居民士气下降。
 * 人口按 7 天周期自然增长（日期与殖民地 id 错开，保证确定性），
 * 但增长本身也要消耗一份物资。</p>
 *
 * <p>升级不由本系统自动完成——升级需要玩家的物资/资金投入，走
 * {@link ColonyService#upgrade}。</p>
 */
public final class ColonySystemImpl implements ColonySystem {

    private static final int GROWTH_PERIOD = 7;
    private static final int MAX_POPULATION = 500;

    @Override
    public void onTick(WorldState state, TickContext ctx) {
        for (Map.Entry<fc.world.Ids.LocationId, ColonyState> e : state.colonies().entrySet()) {
            ColonyState colony = e.getValue();
            if (!colony.isColony()) {
                continue;
            }
            int pop = colony.population();
            BigDecimal foodNeed = BigDecimal.valueOf(Math.max(1, pop / 5));
            BigDecimal waterNeed = foodNeed;

            boolean fed = state.tryConsume(colony.id(), ResourceKind.FOOD, foodNeed, "殖民地口粮");
            boolean watered = state.tryConsume(colony.id(), ResourceKind.WATER, waterNeed, "殖民地饮水");
            if (!fed || !watered) {
                for (Agent a : state.agentsAt(colony.id())) {
                    a.perceive(0, 0, -1);
                }
                ctx.report("殖民地 " + nameOf(state, colony.id())
                        + (fed ? " 缺水" : watered ? " 缺粮" : " 缺粮缺水"));
            }

            if (Math.floorMod(state.now().day() + colony.id().id(), GROWTH_PERIOD) == 0
                    && pop < MAX_POPULATION
                    && state.goods(colony.id(), ResourceKind.FOOD).compareTo(new BigDecimal("2")) >= 0
                    && state.goods(colony.id(), ResourceKind.WATER).compareTo(new BigDecimal("2")) >= 0) {
                state.consume(colony.id(), ResourceKind.FOOD, BigDecimal.ONE, "人口增长口粮");
                state.consume(colony.id(), ResourceKind.WATER, BigDecimal.ONE, "人口增长饮水");
                colony.setPopulation(pop + 1);
                ctx.report("殖民地 " + nameOf(state, colony.id()) + " 人口增至 " + colony.population());
            }
        }
    }

    private static String nameOf(WorldState state, fc.world.Ids.LocationId id) {
        var loc = state.graph().get(id);
        return loc == null ? ("#" + id.id()) : loc.name();
    }
}
