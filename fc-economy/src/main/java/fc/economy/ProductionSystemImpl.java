package fc.economy;

import fc.config.world.RecipeDef;
import fc.sim.spi.ProductionSystem;
import fc.sim.tick.TickContext;
import fc.world.Agent;
import fc.world.Assignment;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Location;
import fc.world.ResourceKind;
import fc.world.WorldState;
import fc.world.WorkUnit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 配方驱动的生产系统（阶段 6 的开工与完工结算）。
 *
 * <p>开工：检查输入齐备（含作为能源的 fuel），一次性扣除输入（经权威账本），
 * 创建 {@link WorkUnit}（工时为配方 ticks）。完工：把产出计入地点库存与账本，
 * 并写一条 {@link GameEvent.ProductionCompleted}。</p>
 *
 * <p>"输入不足就不开工"是 M3 的关键约束：工坊缺燃料就停产，迫使玩家真的去运货。
 * 配方按 id 排序处理，工作单元按 key 排序推进，结果与串行完全一致。</p>
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
        // 每个工人每天只能投入一个工作单元。在制单元的工人先被占用，
        // 否则新配方会把同一批人重复编入两个工作单元。
        Map<Ids.LocationId, java.util.Set<Ids.AgentId>> assigned = new java.util.HashMap<>();
        for (WorkUnit unit : state.workUnits().values()) {
            if (unit.at() != null) {
                assigned.computeIfAbsent(unit.at(), k -> new java.util.HashSet<>())
                        .addAll(unit.workers());
            }
        }

        // 收集每个地点当天可开工的配方（按 id 顺序）。
        Map<Ids.LocationId, List<RecipeDef>> candidates = new java.util.TreeMap<>();
        for (RecipeDef r : economy.recipes()) {
            Ids.LocationId at = new Ids.LocationId(r.at());
            if (state.workUnits().containsKey(key(r.id(), at))) {
                continue;
            }
            if (state.graph().get(at) == null || state.productionDisrupted(at)) {
                continue;
            }
            if (!hasAll(state, at, r.inputs())) {
                continue;
            }
            candidates.computeIfAbsent(at, k -> new ArrayList<>()).add(r);
        }

        for (Map.Entry<Ids.LocationId, List<RecipeDef>> entry : candidates.entrySet()) {
            Ids.LocationId at = entry.getKey();
            java.util.Set<Ids.AgentId> used =
                    assigned.computeIfAbsent(at, k -> new java.util.HashSet<>());
            List<Ids.AgentId> free = new ArrayList<>();
            for (Agent a : state.agentsAt(at)) {
                if (a.assignment() == Assignment.WORKING && !used.contains(a.id())) {
                    free.add(a.id());
                }
            }
            // 设施工位上限：已在制单元的工人也占用工位。
            int capacity = state.workerCapacity(at);
            if (capacity > 0) {
                int remaining = Math.max(0, capacity - used.size());
                if (free.size() > remaining) {
                    free = new ArrayList<>(free.subList(0, remaining));
                }
            }
            if (free.isEmpty()) {
                continue;
            }

            // 轮转分配：让同一地点的多个配方都有机会获得工人。
            List<RecipeDef> recipes = entry.getValue();
            Map<String, List<Ids.AgentId>> allocation = new java.util.LinkedHashMap<>();
            for (RecipeDef r : recipes) {
                allocation.put(key(r.id(), at), new ArrayList<>());
            }
            for (int i = 0; i < free.size(); i++) {
                RecipeDef r = recipes.get(i % recipes.size());
                allocation.get(key(r.id(), at)).add(free.get(i));
            }

            for (RecipeDef r : recipes) {
                List<Ids.AgentId> workers = allocation.get(key(r.id(), at));
                if (workers.isEmpty()) {
                    continue;
                }
                // 同一地点的配方可能争夺同一种输入；后一个配方在扣料前重新核对。
                if (!hasAll(state, at, r.inputs())) {
                    continue;
                }
                for (Map.Entry<String, BigDecimal> e : r.inputs().entrySet()) {
                    state.consume(at, ResourceKind.goodsFromId(e.getKey()), e.getValue(),
                            "配方输入 " + r.id());
                }
                used.addAll(workers);
                state.putWorkUnit(key(r.id(), at),
                        new WorkUnit(r.id(), BigDecimal.ZERO, r.ticks(), workers, at,
                                fc.world.SkillSet.Skill.ENGINEERING));
            }
        }
    }

    @Override
    public void onComplete(WorldState state, TickContext ctx, String key, WorkUnit unit) {
        RecipeDef r = economy.recipe(unit.recipeName()).orElse(null);
        if (r == null) {
            return;
        }
        Ids.LocationId at = unit.at() != null ? unit.at() : new Ids.LocationId(r.at());
        if (state.graph().get(at) == null) {
            return;
        }
        StringBuilder out = new StringBuilder();
        for (String name : new java.util.TreeSet<>(r.outputs().keySet())) {
            ResourceKind kind = ResourceKind.goodsFromId(name);
            BigDecimal amount = r.outputs().get(name)
                    .multiply(techMultiplier(state, kind))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            state.produce(at, kind, amount, "配方产出 " + r.id());
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(name).append('+').append(amount.stripTrailingZeros().toPlainString());
        }
        ctx.events().enqueue(new GameEvent.ProductionCompleted(
                state.now(), at, r.name(), out.toString()));
    }

    /** 科技对产出的乘数（水培农业：农场食物产出 +25%）。 */
    static BigDecimal techMultiplier(WorldState state, ResourceKind kind) {
        BigDecimal factor = BigDecimal.ONE;
        if (kind == ResourceKind.FOOD && state.hasTech("HYDROPONICS")) {
            factor = factor.add(new BigDecimal("0.25"));
        }
        return factor;
    }

    private static boolean hasAll(WorldState state, Ids.LocationId at,
                                  Map<String, BigDecimal> need) {
        for (Map.Entry<String, BigDecimal> e : need.entrySet()) {
            ResourceKind kind = ResourceKind.goodsFromId(e.getKey());
            if (state.goods(at, kind).compareTo(e.getValue()) < 0) {
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
        for (Agent a : state.agentsAt(at)) {
            if (a.assignment() == Assignment.WORKING) {
                n++;
            }
        }
        return n;
    }
}
