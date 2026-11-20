package fc.sim.tick;

import fc.sim.rng.RngStreams;
import fc.world.Agent;
import fc.world.Faction;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Link;
import fc.world.Location;
import fc.world.PendingDecision;
import fc.world.ResourceKind;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 阶段 10：事件引擎（条件触发 + 传播链 + 待决决策）。
 *
 * <p>离子风暴从星域/异常点起源，沿航线逐跳传播（最多 8 个地点、3 跳）。
 * 每个受影响地点留下一条 {@link GameEvent.StormImpact}，因此整条因果链可追溯。
 * 影响包括：生产中断、航线风险上升、士气下降、燃料/能源/材料价格冲击、航程延误。
 * 强风暴可能产生待决决策，玩家必须选择后时间才能继续。</p>
 */
public final class WorldEventStage implements TickStage {

    private static final int MAX_IMPACTS = 8;
    private static final int MAX_DEPTH = 3;

    @Override
    public String name() {
        return "10-event";
    }

    @Override
    public void apply(WorldState state, TickContext ctx) {
        var rng = ctx.rng().stream(RngStreams.EVENTS);

        List<Location> candidates = new ArrayList<>();
        for (Location l : state.graph().nodes().values()) {
            if (l instanceof Location.StarSystem || l instanceof Location.Anomaly) {
                candidates.add(l);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        candidates.sort(Comparator.comparingLong(l -> l.id().id()));

        double probability = 0.02 + (ctx.weather() / 100.0) * 0.05;
        if (!RngStreams.chance(rng, probability)) {
            return;
        }
        Location origin = candidates.get(rng.nextInt(candidates.size()));
        int severity = 1 + rng.nextInt(4);
        propagate(state, ctx, origin, severity);
    }

    private void propagate(WorldState state, TickContext ctx, Location origin, int severity) {
        // 因果顺序：先记录风暴本身，再记录其影响。
        ctx.events().enqueue(new GameEvent.IonStorm(state.now(), origin.id(), severity));

        Map<Ids.LocationId, List<Ids.LocationId>> adj = adjacency(state);
        Map<Ids.LocationId, Integer> depth = new HashMap<>();
        Map<Ids.LocationId, Ids.LocationId> upstream = new LinkedHashMap<>();
        Deque<Ids.LocationId> queue = new ArrayDeque<>();
        Set<Ids.LocationId> visited = new HashSet<>();

        Ids.LocationId start = origin.id();
        queue.add(start);
        depth.put(start, 0);
        visited.add(start);

        int impacts = 0;
        while (!queue.isEmpty() && impacts < MAX_IMPACTS) {
            Ids.LocationId cur = queue.poll();
            int step = depth.get(cur);
            Ids.LocationId parent = upstream.get(cur);
            applyImpact(state, ctx, cur, parent, step, severity);
            impacts++;

            if (step >= MAX_DEPTH) {
                continue;
            }
            for (Ids.LocationId next : adj.getOrDefault(cur, List.of())) {
                if (!visited.add(next)) {
                    continue;
                }
                depth.put(next, step + 1);
                upstream.put(next, cur);
                queue.add(next);
                if (visited.size() > MAX_IMPACTS) {
                    break;
                }
            }
        }

        applyPriceShocks(state, ctx, origin, severity);
        ctx.report("离子风暴 强度 " + severity + " 自 " + origin.name()
                + " 传播 " + impacts + " 处");

        if (severity >= 4 && !state.hasPendingDecision()) {
            raiseAidDecision(state, ctx, origin, severity);
        }
    }

    /**
     * 风暴造成的补给冲击：燃料、能源、材料价格按强度上涨，持续 3 天。
     * 当前价格模型按货物全局定价，因此冲击作用于全局而非单个星系。
     */
    private void applyPriceShocks(WorldState state, TickContext ctx, Location origin,
                                  int severity) {
        BigDecimal multiplier = BigDecimal.ONE.add(
                new BigDecimal("0.05").multiply(BigDecimal.valueOf(severity)));
        for (ResourceKind kind : List.of(
                ResourceKind.FUEL, ResourceKind.ENERGY, ResourceKind.MATERIAL)) {
            state.applyPriceShock(kind, multiplier, 3);
            ctx.events().enqueue(new GameEvent.MarketPriceShocked(
                    state.now(), origin.id(), kind, multiplier));
        }
    }

    private void applyImpact(WorldState state, TickContext ctx, Ids.LocationId where,
                             Ids.LocationId parent, int step, int severity) {
        state.disruptProduction(where, severity >= 3 ? 2 : 1);

        String effect;
        if (step == 0) {
            effect = "生产中断 " + (severity >= 3 ? 2 : 1) + " 天，周边航线风险上升";
        } else {
            effect = "受第 " + step + " 跳波及，生产中断 " + (severity >= 3 ? 2 : 1) + " 天";
        }
        ctx.events().enqueue(new GameEvent.StormImpact(
                state.now(), where, parent, step, severity, effect));

        for (Link link : state.graph().links()) {
            if (link.from().equals(where) || link.to().equals(where)) {
                Ids.LocationId other = link.from().equals(where) ? link.to() : link.from();
                state.addRiskModifier(where, other,
                        new BigDecimal("0.05").multiply(BigDecimal.valueOf(severity)), 2);
            }
        }

        for (Agent a : state.agentsAt(where)) {
            a.perceive(0, 0, -1);
        }

        for (Location.Ship ship : state.graph().ships()) {
            if (ship.inVoyage()
                    && (ship.parent().equals(where) || ship.voyage().to().equals(where))) {
                ship.delayVoyage(1);
            }
        }
    }

    private void raiseAidDecision(WorldState state, TickContext ctx, Location origin,
                                  int severity) {
        BigDecimal cost = BigDecimal.valueOf(200L * severity);
        // 只有风暴源恰好是某势力中心时，声望才有明确归属。
        Ids.FactionId faction = null;
        for (Faction f : state.factions().values()) {
            if (f.seat().equals(origin.id())) {
                faction = f.id();
                break;
            }
        }
        Map<String, BigDecimal> aid = new java.util.LinkedHashMap<>();
        aid.put("credits", cost);
        aid.put("morale", BigDecimal.ONE);
        Map<String, BigDecimal> decline = new java.util.LinkedHashMap<>();
        decline.put("morale", new BigDecimal("-1"));
        String aidText = "支出 " + cost.stripTrailingZeros().toPlainString()
                + " 信用点，提升当地士气。";
        String declineText = "不支出资金，但当地士气受损。";
        if (faction != null) {
            aid.put("reputation", new BigDecimal("5"));
            decline.put("reputation", new BigDecimal("-5"));
            aidText = "支出 " + cost.stripTrailingZeros().toPlainString()
                    + " 信用点，提升声望与士气。";
            declineText = "不支出资金，但声望与当地士气受损。";
        }
        PendingDecision d = state.raiseDecision(
                "风暴救援请求",
                origin.name() + " 遭离子风暴重创（强度 " + severity + "），当地请求援助。",
                origin.id(), faction,
                List.of(
                        new PendingDecision.Option("aid", "提供援助", aidText,
                                PendingDecision.Effect.GRANT_AID, aid),
                        new PendingDecision.Option("decline", "拒绝请求", declineText,
                                PendingDecision.Effect.DECLINE, decline)));
        ctx.events().enqueue(new GameEvent.DecisionRaised(
                state.now(), origin.id(), d.id(), d.title()));
        ctx.report("产生待决决策 " + d.id() + "：" + d.title());
    }

    /** 无向邻接表（邻居按 id 排序，保证传播顺序确定）。 */
    private static Map<Ids.LocationId, List<Ids.LocationId>> adjacency(WorldState state) {
        Map<Ids.LocationId, List<Ids.LocationId>> adj = new TreeMap<>();
        for (Link link : state.graph().links()) {
            adj.computeIfAbsent(link.from(), k -> new ArrayList<>()).add(link.to());
            adj.computeIfAbsent(link.to(), k -> new ArrayList<>()).add(link.from());
        }
        adj.values().forEach(list -> list.sort(Comparator.comparingLong(Ids.LocationId::id)));
        return adj;
    }
}
