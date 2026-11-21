package fc.diplomacy;

import fc.sim.rng.RngStreams;
import fc.sim.spi.ConflictSystem;
import fc.sim.tick.TickContext;
import fc.world.Agent;
import fc.world.Faction;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Location;
import fc.world.MoneyLedger;
import fc.world.ResourceKind;
import fc.world.SkillSet;
import fc.world.Treaty;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 冲突结算器：舰队力量与势力威胁力量的对比，事件化而非战术棋盘。
 *
 * <pre>
 * fleetPower  = Σ max(1, floor(hull / 20)) + 最高 PILOT + 最高 ENGINEERING
 *               + 10 × 介入的同盟数
 * threatPower = 势力基础军力 + floor(reserve / 200)
 *               + floor(max(0, -声望) / 10) + 5 + 额外威胁
 * score       = fleetPower - threatPower + EVENTS 流一次 -5..5
 * </pre>
 *
 * <p>结果：{@code score ≥ 10} 威慑（声望下降，无物资损失）；{@code 0..9} 交火
 * （耐久 10–30、货源 10%–25%）；{@code < 0} 赎金（支付信用点，声望小幅回升）。
 * 有同盟且败北时损失减半并记录援助事件。所有损失只从现有库存扣除。</p>
 */
public final class ConflictResolver implements ConflictSystem {

    /** 势力基础军力按类型。 */
    private static int basePower(Faction f) {
        return switch (f.kind()) {
            case MILITARY -> 20;
            case TRADE -> 12;
            case COLONY -> 10;
            case SCIENCE -> 8;
        };
    }

    @Override
    public Outcome resolve(WorldState state, TickContext ctx, String trigger,
                           Location.Ship ship, Ids.FactionId factionId, int threatBonus) {
        Faction faction = state.faction(factionId);
        if (faction == null) {
            return new Outcome("none", 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                    "无对手");
        }
        int fleet = fleetPower(state);
        int allies = 0;
        for (Ids.FactionId other : state.treaties().keySet()) {
            if (state.hasTreaty(other, Treaty.ALLIANCE)) {
                allies++;
            }
        }
        fleet += 10 * allies;
        int reputation = state.reputation(factionId);
        BigDecimal reserve = state.money().balance(faction.account());
        int threat = basePower(faction)
                + reserve.max(BigDecimal.ZERO)
                        .divide(new BigDecimal("200"), 0, RoundingMode.DOWN).intValue()
                + Math.max(0, -reputation) / 10
                + 5 + Math.max(0, threatBonus);

        int swing = RngStreams.between(ctx.rng().stream(RngStreams.EVENTS), -5, 5);
        int score = fleet - threat + swing;
        boolean allied = state.hasTreaty(factionId, Treaty.ALLIANCE);

        String kind;
        int hullDamage = 0;
        BigDecimal cargoLoss = BigDecimal.ZERO;
        BigDecimal ransom = BigDecimal.ZERO;
        String detail;

        if (score >= 10) {
            kind = "deterrence";
            state.adjustReputation(factionId, -5);
            detail = "我方威慑成功，声望 -5，无物资损失";
        } else if (score >= 0) {
            kind = "firefight";
            hullDamage = RngStreams.between(ctx.rng().stream(RngStreams.EVENTS), 10, 30);
            cargoLoss = cargoLoss(state, ctx, ship, 10, 25);
            if (allied) {
                hullDamage = Math.max(1, hullDamage / 2);
                detail = "同盟介入，交火损失减半";
            } else {
                detail = "双方交火";
            }
            applyHullDamage(state, ctx, ship, hullDamage);
            state.adjustReputation(factionId, -8);
            detail += "：耐久 -" + hullDamage + "，货损 "
                    + cargoLoss.stripTrailingZeros().toPlainString();
        } else {
            kind = "ransom";
            BigDecimal base = BigDecimal.valueOf(100L + Math.max(0, threatBonus) * 10L);
            if (allied) {
                base = base.divide(BigDecimal.TWO, 2, RoundingMode.HALF_UP);
                detail = "同盟援助使赎金减半";
            } else {
                detail = "我方落败，被迫缴纳赎金";
            }
            ransom = base.min(state.credits());
            if (ransom.signum() > 0) {
                state.payFromPlayer(MoneyLedger.factionAccount(factionId), ransom,
                        "冲突赎金 " + faction.name());
            }
            state.adjustReputation(factionId, 3);
            detail += "：" + ransom.stripTrailingZeros().toPlainString() + " 信用点，声望 +3";
        }

        if (allied && score < 0) {
            state.chronicle().record(new GameEvent.FactionAction(
                    state.now(), faction.seat(), factionId, "同盟援助",
                    "同盟势力介入冲突，降低我方损失"));
        }
        ctx.events().enqueue(new GameEvent.ConflictResolved(
                state.now(), ship == null ? faction.seat() : ship.parent(),
                factionId, kind + "：" + detail, fleet, threat, hullDamage, cargoLoss));
        return new Outcome(kind, fleet, threat, hullDamage, cargoLoss, ransom, detail);
    }

    /** 所有玩家舰船的力量合计 + 最高驾驶/工程技能。 */
    private static int fleetPower(WorldState state) {
        int hull = 0;
        int pilot = 0;
        int engineering = 0;
        for (Location.Ship ship : state.graph().ships()) {
            hull += Math.max(1, ship.hull() / 20);
            for (Agent a : state.agentsAt(ship.id())) {
                pilot = Math.max(pilot, a.skills().get(SkillSet.Skill.PILOT));
                engineering = Math.max(engineering,
                        a.skills().get(SkillSet.Skill.ENGINEERING));
            }
        }
        return hull + pilot + engineering;
    }

    private static BigDecimal cargoLoss(WorldState state, TickContext ctx,
                                        Location.Ship ship, int minPercent, int maxPercent) {
        if (ship == null) {
            return BigDecimal.ZERO;
        }
        List<ResourceKind> loaded = new ArrayList<>();
        for (ResourceKind kind : ResourceKind.GOODS) {
            if (state.goods(ship.id(), kind).signum() > 0) {
                loaded.add(kind);
            }
        }
        if (loaded.isEmpty()) {
            return BigDecimal.ZERO;
        }
        var rng = ctx.rng().stream(RngStreams.EVENTS);
        ResourceKind kind = loaded.get(rng.nextInt(loaded.size()));
        int percent = RngStreams.between(rng, minPercent, maxPercent);
        BigDecimal available = state.goods(ship.id(), kind);
        BigDecimal loss = available.multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                .min(available);
        if (loss.signum() > 0) {
            state.tryConsume(ship.id(), kind, loss, "冲突货损");
        }
        return loss;
    }

    private static void applyHullDamage(WorldState state, TickContext ctx,
                                        Location.Ship ship, int damage) {
        List<Location.Ship> targets = new ArrayList<>();
        if (ship != null) {
            targets.add(ship);
        } else {
            targets.addAll(state.graph().ships());
            targets.sort(Comparator.comparingLong(s -> s.shipId().id()));
        }
        if (targets.isEmpty()) {
            return;
        }
        Location.Ship target = targets.get(0);
        target.damage(damage);
        ctx.report("冲突损伤 " + target.name() + " 耐久 -" + damage
                + "（当前 " + target.hull() + "）");
    }
}
