package fc.sim.tick;

import fc.config.world.WorldDefinition;
import fc.sim.rng.RngStreams;
import fc.sim.spi.ConflictSystem;
import fc.world.Agent;
import fc.world.Faction;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Link;
import fc.world.Location;
import fc.world.PendingDecision;
import fc.world.ResourceKind;
import fc.world.SkillSet;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * 阶段 10：事件引擎（配置触发 + 传播链 + 固定反应顺序 + 待决决策）。
 *
 * <p>事件参数由配置的 {@code <events>} 提供；未配置时使用内置默认值。
 * 离子风暴从星域/异常点起源，沿航线逐跳传播并留下可追溯的
 * {@link GameEvent.StormImpact} 因果链。海盗袭击针对在航舰船，造成货损、
 * 耐久损伤与延误。</p>
 *
 * <p>固定反应顺序：直接后果（货损/停产/风险）→ 工程与舰船修理 →
 * 势力响应 → 市场冲击 → 目标/结局检查。顺序固定保证同种子重放一致。</p>
 */
public final class WorldEventStage implements TickStage {

    private static final int DEFAULT_MAX_IMPACTS = 8;
    private static final int DEFAULT_MAX_DEPTH = 3;

    private static final WorldDefinition.EventDef DEFAULT_STORM =
            new WorldDefinition.EventDef("storm", "ION_STORM", 0.02, 0.05, 1, 4, 3, 8);
    private static final WorldDefinition.EventDef DEFAULT_PIRACY =
            new WorldDefinition.EventDef("piracy", "PIRACY", 0.01, 0.03, 1, 3, 0, 1);

    private final ConflictSystem conflicts;

    public WorldEventStage() {
        this(null);
    }

    public WorldEventStage(ConflictSystem conflicts) {
        this.conflicts = conflicts;
    }

    @Override
    public String name() {
        return "10-event";
    }

    @Override
    public void apply(WorldState state, TickContext ctx) {
        List<WorldDefinition.EventDef> rules = ctx.eventRules().isEmpty()
                ? List.of(DEFAULT_STORM, DEFAULT_PIRACY) : ctx.eventRules();
        for (WorldDefinition.EventDef rule : rules) {
            handle(state, ctx, rule);
        }
    }

    private void handle(WorldState state, TickContext ctx, WorldDefinition.EventDef rule) {
        var rng = ctx.rng().stream(RngStreams.EVENTS);
        double probability = rule.probability()
                + (ctx.weather() / 100.0) * rule.weatherFactor();
        if (!RngStreams.chance(rng, Math.min(1.0, probability))) {
            return;
        }
        int severity = RngStreams.between(rng, rule.minSeverity(), rule.maxSeverity());
        if ("ION_STORM".equals(rule.kind())) {
            storm(state, ctx, rng, severity, rule.maxDepth(), rule.maxImpacts());
        } else if ("PIRACY".equals(rule.kind())) {
            piracy(state, ctx, rng, severity);
        }
    }

    private void storm(WorldState state, TickContext ctx, java.util.random.RandomGenerator rng,
                       int severity, int maxDepth, int maxImpacts) {
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
        Location origin = candidates.get(rng.nextInt(candidates.size()));
        propagate(state, ctx, origin, severity, maxDepth, maxImpacts);
    }

    private void propagate(WorldState state, TickContext ctx, Location origin, int severity,
                           int maxDepth, int maxImpacts) {
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
        while (!queue.isEmpty() && impacts < maxImpacts) {
            Ids.LocationId cur = queue.poll();
            int step = depth.get(cur);
            Ids.LocationId parent = upstream.get(cur);
            applyImpact(state, ctx, cur, parent, step, severity);
            impacts++;

            if (step >= maxDepth) {
                continue;
            }
            for (Ids.LocationId next : adj.getOrDefault(cur, List.of())) {
                if (!visited.add(next)) {
                    continue;
                }
                depth.put(next, step + 1);
                upstream.put(next, cur);
                queue.add(next);
                if (visited.size() > maxImpacts) {
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

    /** 海盗袭击：在航舰船的货损、耐久损伤与延误，随后按固定顺序结算反应。 */
    private void piracy(WorldState state, TickContext ctx,
                        java.util.random.RandomGenerator rng, int severity) {
        List<Location.Ship> targets = new ArrayList<>();
        for (Location.Ship ship : state.graph().ships()) {
            if (ship.inVoyage() && ship.operational()) {
                targets.add(ship);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        targets.sort(Comparator.comparingLong(s -> s.shipId().id()));
        Location.Ship ship = targets.get(rng.nextInt(targets.size()));
        Faction rival = factionFor(state, ship);

        // 1) 直接后果：由共享冲突结算器（或内置回退）计算货损、耐久与延误
        BigDecimal loss;
        int hullDamage;
        if (conflicts != null && rival != null) {
            ConflictSystem.Outcome outcome = conflicts.resolve(
                    state, ctx, "piracy", ship, rival.id(), severity * 4);
            loss = outcome.cargoLoss();
            hullDamage = outcome.hullDamage();
            ctx.report("海盗冲突（" + rival.name() + "）：" + outcome.detail());
        } else {
            loss = directPiracyLoss(state, rng, ship);
            hullDamage = severity * RngStreams.between(rng, 3, 8);
            ship.damage(hullDamage);
            ctx.report("海盗袭击 " + ship.name() + "：货损 "
                    + loss.stripTrailingZeros().toPlainString() + "，耐久 -" + hullDamage);
        }
        int delay = 1;
        ship.delayVoyage(delay);
        ctx.events().enqueue(new GameEvent.VoyageIncident(
                state.now(), ship.parent(), ship.shipId(), "海盗袭击", delay, loss, hullDamage));

        // 2) 工程与舰船：船上工程师立即抢修
        int repaired = 0;
        for (Agent a : state.agentsAt(ship.id())) {
            repaired += Math.max(0, a.skills().get(SkillSet.Skill.ENGINEERING) / 2);
        }
        if (repaired > 0) {
            int actual = ship.repair(repaired);
            if (actual > 0) {
                ctx.events().enqueue(new GameEvent.ShipRepaired(
                        state.now(), ship.parent(), ship.shipId(), actual, ship.hull()));
            }
        }

        // 3) 势力响应：军事势力若存在则记录反应
        for (Faction f : state.factions().values()) {
            if (f.kind() == Faction.Kind.MILITARY) {
                ctx.events().enqueue(new GameEvent.FactionAction(
                        state.now(), f.seat(), f.id(), "清剿海盗",
                        "在 " + ship.voyage().from().id() + " 至 "
                                + ship.voyage().to().id() + " 航线巡逻"));
                break;
            }
        }

        // 4) 市场：装备与燃料价格短期上涨
        BigDecimal multiplier = BigDecimal.ONE.add(
                new BigDecimal("0.03").multiply(BigDecimal.valueOf(severity)));
        for (ResourceKind kind : List.of(ResourceKind.EQUIPMENT, ResourceKind.FUEL)) {
            state.applyPriceShock(kind, multiplier, 2);
            ctx.events().enqueue(new GameEvent.MarketPriceShocked(
                    state.now(), ship.parent(), kind, multiplier));
        }

        ctx.report("海盗袭击 " + ship.name() + "：货损 "
                + loss.stripTrailingZeros().toPlainString() + "，耐久 -" + hullDamage);
    }

    /** 内置海盗回退：随机一种已装货物损失 10%–30%，并从账本扣除。 */
    private BigDecimal directPiracyLoss(WorldState state,
                                        java.util.random.RandomGenerator rng,
                                        Location.Ship ship) {
        List<ResourceKind> loaded = new ArrayList<>();
        for (ResourceKind kind : ResourceKind.GOODS) {
            if (ship.cargo().amount(kind).signum() > 0) {
                loaded.add(kind);
            }
        }
        if (loaded.isEmpty()) {
            return BigDecimal.ZERO;
        }
        ResourceKind kind = loaded.get(rng.nextInt(loaded.size()));
        int percent = RngStreams.between(rng, 10, 30);
        BigDecimal loss = ship.cargo().amount(kind)
                .multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                .min(ship.cargo().amount(kind));
        if (loss.signum() > 0) {
            state.consume(ship.id(), kind, loss, "海盗劫掠");
        }
        return loss;
    }

    /** 海盗归属：航线端点控制势力 → 军事势力 → 首个势力。 */
    private static Faction factionFor(WorldState state, Location.Ship ship) {
        Ids.LocationId to = ship.voyage() == null ? ship.parent() : ship.voyage().to();
        Ids.FactionId owner = state.locationOwner(to);
        if (owner != null && state.faction(owner) != null) {
            return state.faction(owner);
        }
        owner = state.locationOwner(ship.parent());
        if (owner != null && state.faction(owner) != null) {
            return state.faction(owner);
        }
        for (Faction f : state.factions().values()) {
            if (f.kind() == Faction.Kind.MILITARY) {
                return f;
            }
        }
        return state.factions().isEmpty() ? null : state.factions().values().iterator().next();
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
        Map<String, BigDecimal> aid = new LinkedHashMap<>();
        aid.put("credits", cost);
        aid.put("morale", BigDecimal.ONE);
        Map<String, BigDecimal> decline = new LinkedHashMap<>();
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
