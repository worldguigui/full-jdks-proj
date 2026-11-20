package fc.sim;

import fc.world.Agent;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.MoneyLedger;
import fc.world.PendingDecision;
import fc.world.WorldState;

import java.math.BigDecimal;

/**
 * 待决决策结算器：把玩家选择的选项后果应用到世界状态。
 *
 * <p>决策由事件引擎在 tick 中产生；玩家在 tick 之外通过 CLI 选择，因此这里直接
 * 写入编年史（玩家即时操作与 tick 事件分属两个入口，见架构文档）。
 * 资金只在账户之间转移，声望与士气被夹在合法区间内。</p>
 */
public final class DecisionResolver {

    private DecisionResolver() {
    }

    /** 结算结果。 */
    public record Result(boolean ok, String message) {
    }

    public static Result resolve(WorldState state, Ids.DecisionId id, String optionId) {
        PendingDecision d = state.decision(id);
        if (d == null) {
            return new Result(false, "决策不存在: " + id);
        }
        if (d.resolved()) {
            return new Result(false, "决策已解决: " + id);
        }
        PendingDecision.Option option = d.option(optionId);
        if (option == null) {
            return new Result(false, "未知选项: " + optionId + "（可选 "
                    + d.options().stream().map(PendingDecision.Option::id).toList() + "）");
        }

        BigDecimal credits = option.params().getOrDefault("credits", BigDecimal.ZERO);
        int reputation = option.params().getOrDefault("reputation", BigDecimal.ZERO).intValue();
        int morale = option.params().getOrDefault("morale", BigDecimal.ZERO).intValue();

        switch (option.effect()) {
            case GRANT_AID -> {
                if (credits.signum() > 0) {
                    if (state.credits().compareTo(credits) < 0) {
                        return new Result(false, "信用点不足：需要 "
                                + credits.stripTrailingZeros().toPlainString() + "，现有 "
                                + state.credits().stripTrailingZeros().toPlainString());
                    }
                    state.payFromPlayer(MoneyLedger.locationAccount(d.where()), credits, "风暴援助");
                }
                applyMorale(state, d.where(), morale);
                applyReputation(state, d.faction(), reputation);
            }
            case DECLINE -> {
                applyMorale(state, d.where(), morale);
                applyReputation(state, d.faction(), reputation);
            }
            case PAY_TRIBUTE -> {
                if (state.credits().compareTo(credits) < 0) {
                    return new Result(false, "信用点不足：需要 "
                            + credits.stripTrailingZeros().toPlainString());
                }
                String to = d.faction() != null ? MoneyLedger.factionAccount(d.faction())
                        : MoneyLedger.TREASURY;
                state.payFromPlayer(to, credits, "缴纳贡金");
                applyReputation(state, d.faction(), reputation);
            }
            case REFUSE_TRIBUTE -> applyReputation(state, d.faction(), reputation);
            case ACCEPT, REROUTE -> {
                // 接受现状/改道：不产生额外资金或声望变化
            }
        }

        d.resolve(optionId);
        String outcome = option.label() + "：" + option.description();
        state.chronicle().record(new GameEvent.DecisionResolved(
                state.now(), d.where(), d.id(), option.id(), outcome));
        return new Result(true, "决策 " + d.id() + " 已选择「" + option.label() + "」。");
    }

    private static void applyMorale(WorldState state, Ids.LocationId where, int delta) {
        if (delta == 0 || where == null) {
            return;
        }
        for (Agent a : state.agentsAt(where)) {
            a.perceive(0, 0, delta);
        }
    }

    private static void applyReputation(WorldState state, Ids.FactionId faction, int delta) {
        if (faction != null && delta != 0) {
            state.adjustReputation(faction, delta);
        }
    }
}
