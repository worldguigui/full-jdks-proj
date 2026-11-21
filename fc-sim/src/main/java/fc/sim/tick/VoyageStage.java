package fc.sim.tick;

import fc.sim.rng.RngStreams;
import fc.world.Agent;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.KnowledgeState;
import fc.world.Location;
import fc.world.ResourceKind;
import fc.world.SkillSet;
import fc.world.SupplyLine;
import fc.world.SurveyTask;
import fc.world.Transfer;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 阶段 7：航行与物流。
 *
 * <p>顺序：舰船事故判定与在航推进 → 测绘推进 → 在途货物运输抵达结算 →
 * 到期的补给线发车。事故只在每次航程判定一次（{@code incidentResolved} 后不再掷骰），
 * 使用 EVENTS 随机流，因此同一存档重放结果一致。</p>
 */
public final class VoyageStage implements TickStage {

    @Override
    public String name() {
        return "7-voyage";
    }

    @Override
    public void apply(WorldState state, TickContext ctx) {
        List<Location.Ship> ships = new ArrayList<>(state.graph().ships());
        ships.sort(Comparator.comparingLong(s -> s.shipId().id()));
        for (Location.Ship ship : ships) {
            if (!ship.inVoyage()) {
                continue;
            }
            rollIncident(state, ctx, ship);
            Ids.LocationId from = ship.voyage().from();
            boolean arrived = ship.advanceVoyage();
            if (arrived) {
                ship.arrive();
                ctx.events().enqueue(new GameEvent.ShipArrived(
                        state.now(), ship.parent(), ship.shipId(), from));
                learnByArrival(state, ctx, ship);
            }
        }

        advanceSurveys(state, ctx);

        for (Transfer t : state.advanceTransfers()) {
            ctx.events().enqueue(new GameEvent.TransferArrived(
                    state.now(), t.to(), t.id(), t.from(), t.resource(), t.amount()));
            ctx.report("运输抵达 " + t.id() + "：" + t.amount().stripTrailingZeros().toPlainString()
                    + " " + t.resource().id() + " → " + t.to().id());
        }

        for (SupplyLine line : state.supplyLines().values()) {
            if (!line.due()) {
                continue;
            }
            BigDecimal available = state.goods(line.from(), line.resource());
            if (available.compareTo(line.amountPerRun()) < 0) {
                ctx.report("补给线 " + line.id() + " 货源不足（需 "
                        + line.amountPerRun().stripTrailingZeros().toPlainString()
                        + "，仅有 " + available.stripTrailingZeros().toPlainString() + "），本批跳过");
                continue;
            }
            Transfer t = state.startTransfer(line.from(), line.to(), line.resource(),
                    line.amountPerRun(), line.travelTicks(), "补给线 " + line.id());
            line.countDelivery();
            state.recordSupplyDispatch();
            ctx.events().enqueue(new GameEvent.TransferStarted(
                    state.now(), line.to(), t.id(), t.from(), t.to(), t.resource(),
                    t.amount(), t.totalTicks()));
        }
    }

    /** 每次航程只判定一次事故；风险在启航时已经算入航程记录。 */
    private void rollIncident(WorldState state, TickContext ctx, Location.Ship ship) {
        var voyage = ship.voyage();
        if (voyage.incidentResolved() || voyage.incidentChance() <= 0) {
            return;
        }
        ship.markIncidentResolved();
        var rng = ctx.rng().stream(RngStreams.EVENTS);
        if (!RngStreams.chance(rng, Math.min(0.90, voyage.incidentChance()))) {
            return;
        }
        int delay = RngStreams.between(rng, 1, 3);
        int hullDamage = RngStreams.between(rng, 5, 20);
        BigDecimal loss = lossOf(state, ctx, rng, ship);
        ship.damage(hullDamage);
        ship.delayVoyage(delay);
        ctx.events().enqueue(new GameEvent.VoyageIncident(
                state.now(), ship.parent(), ship.shipId(), "航行事故", delay, loss, hullDamage));
        ctx.report("航行事故 " + ship.name() + "：延误 " + delay + " 天，耐久 -"
                + hullDamage + "，货损 " + loss.stripTrailingZeros().toPlainString()
                + "（当前耐久 " + ship.hull() + "）");
        if (!ship.operational()) {
            ctx.report("⚠ " + ship.name() + " 耐久归零，已瘫痪在航，需维修后继续");
        }
    }

    /** 随机损失一种已装载货物 10%..35%。 */
    private BigDecimal lossOf(WorldState state, TickContext ctx,
                             java.util.random.RandomGenerator rng, Location.Ship ship) {
        List<ResourceKind> loaded = new ArrayList<>();
        for (ResourceKind kind : ResourceKind.GOODS) {
            if (state.goods(ship.id(), kind).signum() > 0) {
                loaded.add(kind);
            }
        }
        if (loaded.isEmpty()) {
            return BigDecimal.ZERO;
        }
        ResourceKind kind = loaded.get(rng.nextInt(loaded.size()));
        int percent = RngStreams.between(rng, 10, 35);
        BigDecimal amount = state.goods(ship.id(), kind)
                .multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        state.tryConsume(ship.id(), kind, amount, "航行事故货损");
        return amount;
    }

    /** 抵达未知地点：DETECTED/SURVEYED，并记录知识事件。 */
    private void learnByArrival(WorldState state, TickContext ctx, Location.Ship ship) {
        Ids.LocationId at = ship.parent();
        KnowledgeState before = state.knowledgeOf(at);
        if (before.atLeast(KnowledgeState.SURVEYED)) {
            return;
        }
        // 只有启用星图知识的世界（装配时已有知识表）才记录知识事件；
        // 手工构造的裸世界保持旧编年史语义。
        boolean tracksKnowledge = !state.knowledge().isEmpty();
        state.setKnowledge(at, KnowledgeState.SURVEYED);
        if (tracksKnowledge) {
            ctx.events().enqueue(new GameEvent.KnowledgeGained(
                    state.now(), at, KnowledgeState.SURVEYED,
                    ship.name() + " 抵达并完成近距离勘察"));
            ctx.report("星图更新：" + at.id() + " → "
                    + KnowledgeState.SURVEYED.displayName());
        }
    }

    /** 推进测绘任务：舰船必须停泊在目标地点。 */
    private void advanceSurveys(WorldState state, TickContext ctx) {
        Map<Ids.LocationId, SurveyTask> surveys = state.surveys();
        for (Map.Entry<Ids.LocationId, SurveyTask> e : surveys.entrySet()) {
            SurveyTask task = e.getValue();
            Optional<Location.Ship> maybe = findShip(state, task.ship());
            if (maybe.isEmpty()) {
                continue;
            }
            Location.Ship ship = maybe.get();
            if (ship.inVoyage() || !ship.parent().equals(task.target())) {
                continue;
            }
            // 工期已在立项时按探索技能与测绘无人机折算；停泊期间每天推进 1 天。
            task.advance(BigDecimal.ONE);
            if (!task.done()) {
                continue;
            }
            completeSurvey(state, ctx, task, ship);
        }
    }

    private static int explorationSkill(WorldState state, Location.Ship ship) {
        int best = 0;
        for (Agent a : state.agentsAt(ship.id())) {
            best = Math.max(best, a.skills().get(SkillSet.Skill.EXPLORATION));
        }
        return best;
    }

    private void completeSurvey(WorldState state, TickContext ctx, SurveyTask task,
                                Location.Ship ship) {
        var target = task.target();
        state.setKnowledge(target, KnowledgeState.CHARTED);
        int skill = explorationSkill(state, ship);
        int yield = 1 + skill / 4;
        task.setResearchYield(yield);
        // 科研数据进入舰船货舱，不超过剩余舱容。
        BigDecimal loaded = BigDecimal.valueOf(yield).min(ship.cargoFree());
        if (loaded.signum() > 0) {
            state.produce(ship.id(), ResourceKind.RESEARCH, loaded, "测绘完成 " + target.id());
        }

        var rng = ctx.rng().stream(RngStreams.EVENTS);
        String discovery = "已绘制完整星图";
        ResourceKind find = null;
        BigDecimal amount = BigDecimal.ZERO;
        double discoveryChance = 0.10 + skill * 0.03;
        if (RngStreams.chance(rng, Math.min(0.90, discoveryChance))) {
            List<ResourceKind> kinds = List.of(ResourceKind.ORE, ResourceKind.MATERIAL,
                    ResourceKind.LUXURY);
            find = kinds.get(rng.nextInt(kinds.size()));
            amount = BigDecimal.valueOf(RngStreams.between(rng, 5, 20));
            state.produce(target, find, amount, "测绘发现 " + target.id());
            discovery = "发现" + find.displayName() + "矿藏 " + amount.stripTrailingZeros().toPlainString();
        }
        ctx.events().enqueue(new GameEvent.SurveyCompleted(
                state.now(), target, task.ship(), yield, discovery));
        ctx.events().enqueue(new GameEvent.DiscoveryMade(
                state.now(), target, discovery, find, amount));
        state.removeSurvey(target);
        // 测绘结束：船员回到普通工作指派，避免永久停留在 SURVEYING。
        for (Agent a : state.agentsAt(ship.id())) {
            if (a.assignment() == fc.world.Assignment.SURVEYING) {
                a.assign(fc.world.Assignment.WORKING);
            }
        }
        ctx.report("测绘完成 " + target.id() + "：科研数据 +" + yield + "；" + discovery);
    }

    private static Optional<Location.Ship> findShip(WorldState state, Ids.ShipId id) {
        for (Location.Ship ship : state.graph().ships()) {
            if (ship.shipId().equals(id)) {
                return Optional.of(ship);
            }
        }
        return Optional.empty();
    }
}
