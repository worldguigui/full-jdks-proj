package fc.economy;

import fc.config.world.RecipeDef;
import fc.sim.spi.ProductionSystem;
import fc.sim.tick.TickContext;
import fc.world.Agent;
import fc.world.Assignment;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Location;
import fc.world.LocationOps;
import fc.world.Stock;
import fc.world.WorldState;
import fc.world.WorkUnit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 配方驱动的生产系统（阶段 6 的开工与完工结算）。
 *
 * <p>开工：检查输入齐备（含作为能源的 fuel），一次性扣除输入，创建 {@link WorkUnit}（工时为配方 ticks）。
 * 完工：把产出计入地点库存与账本，并写一条 {@link GameEvent.ProductionCompleted}。</p>
 *
 * <p>"输入不足就不开工"是 M3 的关键约束：工坊缺燃料就停产，迫使玩家真的去运货。</p>
 */
public final class ProductionSystemImpl implements ProductionSystem {

    private final Economy economy;

    public ProductionSystemImpl(Economy economy) {
        this.economy = economy;
    }

    @Override
    public void startUnits(WorldState state, TickContext ctx) {
        if (!economy.enabled()) {
            return;
        }
        for (RecipeDef r : economy.recipes()) {
            Ids.LocationId at = new Ids.LocationId(r.at());
            String key = key(r.id(), at);
            if (state.workUnits().containsKey(key)) {
                continue;
            }
            Location loc = state.graph().get(at);
            if (loc == null) {
                continue;
            }
            if (!hasAll(loc.stock(), r.inputs())) {
                continue;
            }
            // 扣输入（分区视图 + 权威账本）
            Stock stock = loc.stock();
            for (Map.Entry<String, BigDecimal> e : r.inputs().entrySet()) {
                stock = stock.subtract(e.getKey(), e.getValue());
            }
            state.graph().replace(LocationOps.withStock(loc, stock));
            for (Map.Entry<String, BigDecimal> e : r.inputs().entrySet()) {
                state.ledger().debit(e.getKey(), e.getValue());
            }

            List<Ids.AgentId> workers = new ArrayList<>();
            state.agents().values().stream()
                    .filter(a -> a.location().equals(at) && a.assignment() == Assignment.WORKING)
                    .sorted(Comparator.comparingLong(a -> a.id().id()))
                    .forEach(a -> workers.add(a.id()));

            state.putWorkUnit(key, new WorkUnit(r.id(), 0, r.ticks(), workers, at));
        }
    }

    @Override
    public void onComplete(WorldState state, TickContext ctx, String key, WorkUnit unit) {
        RecipeDef r = economy.recipe(unit.recipeName()).orElse(null);
        if (r == null) {
            return;
        }
        Location loc = state.graph().get(r.at() == 0 ? unit.at() : new Ids.LocationId(r.at()));
        if (loc == null) {
            return;
        }
        Stock stock = loc.stock();
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, BigDecimal> e : r.outputs().entrySet()) {
            stock = stock.add(e.getKey(), e.getValue());
            state.ledger().credit(e.getKey(), e.getValue());
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(e.getKey()).append('+').append(e.getValue().stripTrailingZeros().toPlainString());
        }
        state.graph().replace(LocationOps.withStock(loc, stock));
        ctx.events().enqueue(new GameEvent.ProductionCompleted(
                state.now(), loc.id(), r.name(), out.toString()));
    }

    private static boolean hasAll(Stock stock, Map<String, BigDecimal> need) {
        for (Map.Entry<String, BigDecimal> e : need.entrySet()) {
            if (stock.amount(e.getKey()).compareTo(e.getValue()) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String key(String recipeId, Ids.LocationId at) {
        return recipeId + "@" + at.id();
    }

    /** 便于 CLI 展示：某地点是否有正在进行的生产。 */
    public static boolean busy(WorldState state, Ids.LocationId at) {
        for (WorkUnit u : state.workUnits().values()) {
            if (at.equals(u.at())) {
                return true;
            }
        }
        return false;
    }

    /** 该地点在岗人数（决定生产速度）。 */
    public static int workingAgents(WorldState state, Ids.LocationId at) {
        int n = 0;
        for (Agent a : state.agents().values()) {
            if (a.location().equals(at) && a.assignment() == Assignment.WORKING) {
                n++;
            }
        }
        return n;
    }
}
