package fc.sim.tick;

import fc.world.Agent;
import fc.world.GameEvent;
import fc.world.MoneyLedger;
import fc.world.Trait;
import fc.world.WorldState;

import java.math.BigDecimal;

/**
 * 阶段 8：消耗与维护（日常口粮 + 贷款利息）。
 *
 * <p>与阶段 5 的分工：阶段 5 只在「饥荒危急」时做生存决策；
 * 阶段 8 负责**日常进食**（饥饿达到 55 就吃，缓解 25），
 * 因此口粮是被持续消耗的，缺粮会真实造成饥饿累积与士气下降。</p>
 *
 * <p>维护项：玩家贷款按配置日利率计息；资金不足的部分计入本金（见
 * {@link MoneyLedger#chargeInterest}）。</p>
 */
public final class ConsumptionStage implements TickStage {

    private static final int EAT_THRESHOLD = 55;
    private static final int RELIEF = 25;

    @Override
    public String name() {
        return "8-consumption";
    }

    @Override
    public void apply(WorldState state, TickContext ctx) {
        for (Agent a : Agents.sorted(state)) {
            if (!Eating.wantsFood(a, EAT_THRESHOLD) || a.ateOn(state.now().day())) {
                continue;
            }
            int before = a.needs().hunger();
            if (Eating.tryEat(state, a, RELIEF)) {
                // 仅在饥饿严重时记录，避免日志被日常进食淹没
                if (before >= 70) {
                    ctx.events().enqueue(new GameEvent.CrewAte(
                            state.now(), a.location(), a.id(), before));
                }
            } else {
                // 缺粮：士气受挫（STOIC 抗压）
                a.perceive(0, 0, a.trait() == Trait.STOIC ? -1 : -2);
                if (before >= 80 && !a.starvingAlerted()) {
                    ctx.events().enqueue(new GameEvent.CrewStarving(
                            state.now(), a.location(), a.id(), before));
                    a.setStarvingAlerted(true);
                }
            }
        }

        BigDecimal rate = ctx.loanDailyRate();
        if (rate.signum() > 0) {
            BigDecimal interest = state.money().chargeInterest(
                    state.now(), MoneyLedger.PLAYER, rate);
            if (interest.signum() > 0) {
                ctx.report("贷款利息 " + interest.stripTrailingZeros().toPlainString()
                        + "，欠款 " + state.money().debt(MoneyLedger.PLAYER)
                                .stripTrailingZeros().toPlainString());
            }
        }
    }
}
