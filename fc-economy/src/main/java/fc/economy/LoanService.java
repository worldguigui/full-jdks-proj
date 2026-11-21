package fc.economy;

import fc.world.MoneyLedger;
import fc.world.WorldState;

import java.math.BigDecimal;

/**
 * 贷款服务：玩家与银行之间的借款、还款。
 *
 * <p>规则来自 M4 规格：单笔借款与未偿总额都不得超过银行当前余额；
 * 还款不得超过债务或玩家余额。所有资金转移走 {@link MoneyLedger}，
 * 利息由消耗阶段按未偿本金逐日计提。</p>
 */
public final class LoanService {

    private LoanService() {
    }

    public record Result(boolean ok, String message) {
    }

    public static Result borrow(WorldState state, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return new Result(false, "借款金额必须为正");
        }
        BigDecimal bank = state.money().balance(MoneyLedger.BANK);
        if (amount.compareTo(bank) > 0) {
            return new Result(false, "单笔借款超过银行余额：" + bank.stripTrailingZeros().toPlainString());
        }
        BigDecimal owed = state.money().debt(MoneyLedger.PLAYER);
        if (owed.add(amount).compareTo(bank) > 0) {
            return new Result(false, "未偿总额将超过银行余额：已欠 "
                    + owed.stripTrailingZeros().toPlainString() + "，银行 "
                    + bank.stripTrailingZeros().toPlainString());
        }
        state.money().borrow(state.now(), MoneyLedger.PLAYER, amount, "玩家贷款");
        return new Result(true, "借款 " + amount.stripTrailingZeros().toPlainString()
                + "，未偿 " + state.money().debt(MoneyLedger.PLAYER)
                        .stripTrailingZeros().toPlainString()
                + "，余额 " + state.credits().stripTrailingZeros().toPlainString());
    }

    public static Result repay(WorldState state, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return new Result(false, "还款金额必须为正");
        }
        BigDecimal owed = state.money().debt(MoneyLedger.PLAYER);
        if (owed.signum() == 0) {
            return new Result(false, "当前没有未偿债务");
        }
        if (amount.compareTo(owed) > 0) {
            return new Result(false, "还款超过债务：欠款 "
                    + owed.stripTrailingZeros().toPlainString());
        }
        if (state.credits().compareTo(amount) < 0) {
            return new Result(false, "信用点不足：需要 "
                    + amount.stripTrailingZeros().toPlainString() + "，仅有 "
                    + state.credits().stripTrailingZeros().toPlainString());
        }
        state.money().repay(state.now(), MoneyLedger.PLAYER, amount, "玩家还款");
        return new Result(true, "还款 " + amount.stripTrailingZeros().toPlainString()
                + "，剩余欠款 " + state.money().debt(MoneyLedger.PLAYER)
                        .stripTrailingZeros().toPlainString());
    }
}
