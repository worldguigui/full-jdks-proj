package fc.diplomacy;

import fc.sim.tick.TickContext;
import fc.world.Commission;
import fc.world.Faction;
import fc.world.GameEvent;
import fc.world.Location;
import fc.world.MoneyLedger;
import fc.world.ResourceKind;
import fc.world.WorldState;

import java.math.BigDecimal;

/**
 * 委托服务（CLI 入口）：查看、接取、交付与逾期结算。
 *
 * <p>委托要求把指定货物运到交付地点：接取本身不占用货舱；交付时舰船必须停泊在
 * 目的地且货舱数量足够。报酬从势力账户转入玩家账户，不足部分按账户余额支付，
 * 不产生负余额。逾期由势力阶段统一判定。</p>
 */
public final class CommissionService {

    private CommissionService() {
    }

    public record Result(boolean ok, String message) {
    }

    public static String list(WorldState state) {
        if (state.commissions().isEmpty()) {
            return "当前没有委托。势力在优先货物短缺时会发布采购委托。";
        }
        StringBuilder sb = new StringBuilder("---- 势力委托 ----\n");
        for (Commission c : state.commissions().values()) {
            Faction f = state.faction(c.faction());
            sb.append("  ").append(c.id()).append(' ').append(c.status())
                    .append("｜").append(f == null ? ("#" + c.faction().id()) : f.name())
                    .append("｜").append(c.amount().stripTrailingZeros().toPlainString())
                    .append(' ').append(c.good().id())
                    .append(" → ").append(c.destination().id())
                    .append("｜截止 ").append(c.deadline().toIsoDate())
                    .append("｜报酬 ").append(c.reward().stripTrailingZeros().toPlainString())
                    .append('\n');
        }
        return sb.toString();
    }

    public static Result accept(WorldState state, String id) {
        Commission c = state.commission(id);
        if (c == null) {
            return new Result(false, "委托不存在: " + id);
        }
        if (c.status() != Commission.Status.OFFERED) {
            return new Result(false, "委托当前不可接取: " + c.status());
        }
        c.accept();
        state.chronicle().record(new GameEvent.CommissionAccepted(
                state.now(), c.destination(), c.id()));
        return new Result(true, "已接取委托 " + c.id() + "："
                + c.amount().stripTrailingZeros().toPlainString() + " " + c.good().id()
                + " 送往 " + c.destination().id() + "，截止 " + c.deadline().toIsoDate());
    }

    /** 交付：优先使用指定舰船，否则寻找停泊在目的地且货物足够的舰船。 */
    public static Result deliver(WorldState state, String id, Location.Ship preferred) {
        Commission c = state.commission(id);
        if (c == null) {
            return new Result(false, "委托不存在: " + id);
        }
        if (c.status() != Commission.Status.ACCEPTED) {
            return new Result(false, "委托尚未接取或已完成: " + c.status());
        }
        Location.Ship ship = preferred;
        if (ship == null || !ship.parent().equals(c.destination())
                || state.goods(ship.id(), c.good()).compareTo(c.amount()) < 0) {
            ship = null;
            for (Location.Ship s : state.graph().ships()) {
                if (s.parent().equals(c.destination()) && !s.inVoyage()
                        && state.goods(s.id(), c.good()).compareTo(c.amount()) >= 0) {
                    ship = s;
                    break;
                }
            }
        }
        if (ship == null) {
            return new Result(false, "需要一艘停泊在 " + c.destination().id()
                    + " 且装有 " + c.amount().stripTrailingZeros().toPlainString()
                    + " " + c.good().id() + " 的舰船");
        }
        Faction f = state.faction(c.faction());
        if (f == null) {
            return new Result(false, "委托势力不存在");
        }
        state.consume(ship.id(), c.good(), c.amount(), "委托交付 " + c.id());
        BigDecimal balance = state.money().balance(MoneyLedger.factionAccount(c.faction()));
        BigDecimal paid = c.reward().min(balance);
        if (paid.signum() > 0) {
            state.money().transfer(state.now(), MoneyLedger.factionAccount(c.faction()),
                    MoneyLedger.PLAYER, paid, "委托报酬 " + c.id());
        }
        c.complete();
        state.adjustReputation(c.faction(), 10);
        state.chronicle().record(new GameEvent.CommissionCompleted(
                state.now(), c.destination(), c.id(), paid, 10));
        return new Result(true, "委托完成 " + c.id() + "：报酬 "
                + paid.stripTrailingZeros().toPlainString() + "，声望 +10");
    }

    /** 势力阶段调用：逾期未交付的委托失败。 */
    public static int failExpired(WorldState state, TickContext ctx) {
        int failed = 0;
        for (Commission c : state.commissions().values()) {
            if (c.status() != Commission.Status.ACCEPTED
                    || state.now().day() <= c.deadline().day()) {
                continue;
            }
            c.fail();
            state.adjustReputation(c.faction(), -5);
            ctx.events().enqueue(new GameEvent.CommissionFailed(
                    state.now(), c.destination(), c.id(), -5));
            ctx.report("委托逾期：" + c.id() + "，声望 -5");
            failed++;
        }
        return failed;
    }
}
