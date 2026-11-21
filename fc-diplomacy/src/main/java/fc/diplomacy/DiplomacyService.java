package fc.diplomacy;

import fc.sim.tick.TickContext;
import fc.world.Commission;
import fc.world.Faction;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Location;
import fc.world.MoneyLedger;
import fc.world.Treaty;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 外交服务（CLI 入口）：赠礼、条约与威胁。
 *
 * <p>外交动作要求一艘玩家舰船停泊在该势力中心。声望分级为
 * 敌对 ≤ −40、戒备 −39..−1、中立 0..19、知名 20..49、信任 50..79、同盟 80+。
 * 赠礼每 100 信用点提升 1 点声望（单次至少 1、至多 5）；条约有声望与资金门槛，
 * 同盟还要求完成过该势力的一次委托。</p>
 */
public final class DiplomacyService {

    private DiplomacyService() {
    }

    /** 声望分级。 */
    public enum Tier {
        HOSTILE("敌对", -1000, -40),
        WARY("戒备", -39, -1),
        NEUTRAL("中立", 0, 19),
        KNOWN("知名", 20, 49),
        TRUSTED("信任", 50, 79),
        ALLIED("同盟", 80, 1000);

        private final String display;
        private final int min;
        private final int max;

        Tier(String display, int min, int max) {
            this.display = display;
            this.min = min;
            this.max = max;
        }

        public String displayName() {
            return display;
        }

        public static Tier of(int reputation) {
            for (Tier t : values()) {
                if (reputation >= t.min && reputation <= t.max) {
                    return t;
                }
            }
            return NEUTRAL;
        }
    }

    /** 操作结果。 */
    public record Result(boolean ok, String message) {
    }

    /** 停泊在势力中心的玩家舰船（按 id 排序取第一艘可用舰船）。 */
    public static Location.Ship playerShipAt(WorldState state, Ids.LocationId seat) {
        for (Location.Ship ship : state.graph().ships()) {
            if (ship.parent().equals(seat) && !ship.inVoyage() && ship.operational()) {
                return ship;
            }
        }
        return null;
    }

    public static Result gift(WorldState state, Ids.FactionId factionId, BigDecimal amount) {
        Faction faction = state.faction(factionId);
        if (faction == null) {
            return new Result(false, "势力不存在: " + factionId.id());
        }
        if (amount == null || amount.signum() <= 0) {
            return new Result(false, "赠礼金额必须为正");
        }
        if (playerShipAt(state, faction.seat()) == null) {
            return new Result(false, "需要一艘可用舰船停泊在 " + faction.seat().id());
        }
        if (state.credits().compareTo(amount) < 0) {
            return new Result(false, "信用点不足：" + state.credits().stripTrailingZeros().toPlainString());
        }
        int gain = Math.max(1, Math.min(5, amount.divide(new BigDecimal("100"), 0,
                java.math.RoundingMode.DOWN).intValue()));
        state.payFromPlayer(MoneyLedger.factionAccount(factionId), amount,
                "赠礼 " + faction.name());
        state.adjustReputation(factionId, gain);
        state.chronicle().record(new GameEvent.DiplomacyAction(
                state.now(), faction.seat(), factionId, "赠礼",
                amount.stripTrailingZeros().toPlainString() + " 信用点，声望 +" + gain));
        return new Result(true, "向 " + faction.name() + " 赠礼 "
                + amount.stripTrailingZeros().toPlainString() + "，声望 +" + gain
                + "（当前 " + state.reputation(factionId) + "）");
    }

    /** 条约门槛：声望与费用。 */
    private static int minimumReputation(Treaty treaty) {
        return switch (treaty) {
            case TRADE -> 20;
            case NON_AGGRESSION -> 50;
            case ALLIANCE -> 80;
        };
    }

    private static BigDecimal cost(Treaty treaty) {
        return switch (treaty) {
            case TRADE -> new BigDecimal("200");
            case NON_AGGRESSION -> new BigDecimal("500");
            case ALLIANCE -> new BigDecimal("1000");
        };
    }

    public static Result treaty(WorldState state, Ids.FactionId factionId, Treaty treaty) {
        Faction faction = state.faction(factionId);
        if (faction == null) {
            return new Result(false, "势力不存在: " + factionId.id());
        }
        if (state.hasTreaty(factionId, treaty)) {
            return new Result(false, "已签署" + treaty.displayName());
        }
        if (playerShipAt(state, faction.seat()) == null) {
            return new Result(false, "需要一艘可用舰船停泊在 " + faction.seat().id());
        }
        int reputation = state.reputation(factionId);
        int need = minimumReputation(treaty);
        if (reputation < need) {
            return new Result(false, "声望不足：" + reputation + " / 需要 " + need);
        }
        if (treaty == Treaty.ALLIANCE && !completedCommissionFor(state, factionId)) {
            return new Result(false, "同盟需要先完成一次该势力的委托");
        }
        BigDecimal fee = cost(treaty);
        if (state.credits().compareTo(fee) < 0) {
            return new Result(false, "信用点不足：需要 "
                    + fee.stripTrailingZeros().toPlainString());
        }
        state.payFromPlayer(MoneyLedger.factionAccount(factionId), fee,
                "签署" + treaty.displayName());
        state.addTreaty(factionId, treaty);
        state.chronicle().record(new GameEvent.TreatySigned(
                state.now(), faction.seat(), factionId, treaty, fee));
        return new Result(true, "与 " + faction.name() + " 签署" + treaty.displayName()
                + "，花费 " + fee.stripTrailingZeros().toPlainString());
    }

    private static boolean completedCommissionFor(WorldState state, Ids.FactionId faction) {
        for (Commission c : state.commissions().values()) {
            if (c.faction().equals(faction) && c.status() == Commission.Status.COMPLETED) {
                return true;
            }
        }
        return false;
    }

    /** 威胁：立即进入冲突结算，声望下降。 */
    public static Result threaten(WorldState state, TickContext ctx, Ids.FactionId factionId) {
        Faction faction = state.faction(factionId);
        if (faction == null) {
            return new Result(false, "势力不存在: " + factionId.id());
        }
        Location.Ship ship = playerShipAt(state, faction.seat());
        if (ship == null) {
            return new Result(false, "需要一艘可用舰船停泊在 " + faction.seat().id());
        }
        state.adjustReputation(factionId, -10);
        state.chronicle().record(new GameEvent.DiplomacyAction(
                state.now(), faction.seat(), factionId, "威胁",
                "舰队进入 " + faction.name() + " 中心，声望 -10"));
        ConflictResolver resolver = new ConflictResolver();
        ConflictResolver.Outcome outcome = resolver.resolve(
                state, ctx, "threaten", ship, factionId, 0);
        return new Result(true, "威胁 " + faction.name() + "：" + outcome.detail()
                + "（声望 " + state.reputation(factionId) + "）");
    }

    /** 势力面板：级差、税率、条约与委托。 */
    public static String panel(WorldState state) {
        StringBuilder sb = new StringBuilder("---- 势力与外交 ----\n");
        for (Faction f : state.factions().values()) {
            int rep = state.reputation(f.id());
            sb.append("  #").append(f.id().id()).append(' ').append(f.name())
                    .append("（").append(f.kind()).append("）")
                    .append(" 声望 ").append(rep).append(' ')
                    .append(Tier.of(rep).displayName())
                    .append("｜税率 ").append(state.taxRateAt(f.seat())
                            .stripTrailingZeros().toPlainString())
                    .append("｜中心 ").append(f.seat().id()).append('\n');
            Map<Ids.FactionId, java.util.Set<Treaty>> treaties = state.treaties();
            sb.append("      条约：");
            if (!treaties.containsKey(f.id()) || treaties.get(f.id()).isEmpty()) {
                sb.append("（无）");
            } else {
                treaties.get(f.id()).stream().sorted()
                        .forEach(t -> sb.append(t.displayName()).append(' '));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
