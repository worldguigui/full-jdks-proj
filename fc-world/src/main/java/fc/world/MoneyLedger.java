package fc.world;

import fc.world.Ids.LocationId;
import fc.world.time.Stardate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 财务账本：信用点的唯一存放处。
 *
 * <p>信用点不进入 {@link Stock}。玩家、地点、势力、居民家庭与银行都是账本账户；
 * 交易、工资、贷款都在账户之间转移，账本始终记录每笔流水，便于查询收支与审计。</p>
 *
 * <p>线程安全：synchronized；金额使用 {@link BigDecimal}，禁止浮点。</p>
 */
public final class MoneyLedger {

    public static final String PLAYER = "player";
    public static final String HOUSEHOLDS = "households";
    public static final String BANK = "bank";
    public static final String TREASURY = "treasury";

    /** 一笔资金流水。 */
    public record Transaction(Stardate when, String from, String to, BigDecimal amount, String reason) {
        public Transaction {
            amount = amount.stripTrailingZeros();
        }
    }

    private final Map<String, BigDecimal> accounts = new LinkedHashMap<>();
    private final List<Transaction> transactions = new ArrayList<>();
    private final Map<String, BigDecimal> debts = new LinkedHashMap<>();

    public static String locationAccount(LocationId id) {
        return "loc:" + id.id();
    }

    public static String factionAccount(Ids.FactionId id) {
        return "faction:" + id.id();
    }

    /** 开立账户（初始资金；重复开立会覆盖，仅装配期使用）。 */
    public synchronized void open(String account, BigDecimal initial) {
        if (initial.signum() < 0) {
            throw new IllegalArgumentException("账户初始余额不得为负: " + account + "=" + initial);
        }
        accounts.put(account, initial.stripTrailingZeros());
    }

    /** 账户余额（不存在为 0）。 */
    public synchronized BigDecimal balance(String account) {
        return accounts.getOrDefault(account, BigDecimal.ZERO);
    }

    /** 全部账户余额（只读快照）。 */
    public synchronized Map<String, BigDecimal> balances() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(accounts));
    }

    /** 账户资金总额（用于守恒断言）。 */
    public synchronized BigDecimal total() {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : accounts.values()) {
            sum = sum.add(v);
        }
        return sum;
    }

    /**
     * 账户间转账。付款方余额不足时抛异常（不产生负余额）。
     */
    public synchronized void transfer(Stardate when, String from, String to, BigDecimal amount, String reason) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("转账金额必须为正: " + amount);
        }
        if (from.equals(to)) {
            return;
        }
        BigDecimal fromBalance = accounts.getOrDefault(from, BigDecimal.ZERO);
        if (fromBalance.compareTo(amount) < 0) {
            throw new IllegalArgumentException("账户余额不足: " + from + " 需 " + amount
                    + " 仅有 " + fromBalance);
        }
        accounts.put(from, fromBalance.subtract(amount));
        accounts.merge(to, amount, BigDecimal::add);
        transactions.add(new Transaction(when, from, to, amount, reason));
    }

    /** 只记收入（初始/外部注资；不用于常规结算）。 */
    public synchronized void deposit(Stardate when, String to, BigDecimal amount, String reason) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("入账金额必须为正: " + amount);
        }
        accounts.merge(to, amount, BigDecimal::add);
        transactions.add(new Transaction(when, "external", to, amount, reason));
    }

    /** 账户欠款（无欠款为 0）。 */
    public synchronized BigDecimal debt(String account) {
        return debts.getOrDefault(account, BigDecimal.ZERO);
    }

    /** 从银行借款：银行账户向借款方转账，并记入欠款。 */
    public synchronized void borrow(Stardate when, String account, BigDecimal amount, String reason) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("借款金额必须为正: " + amount);
        }
        transfer(when, BANK, account, amount, reason);
        debts.merge(account, amount, BigDecimal::add);
    }

    /** 还款：借款方向银行转账，欠款不超过剩余本金。 */
    public synchronized void repay(Stardate when, String account, BigDecimal amount, String reason) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("还款金额必须为正: " + amount);
        }
        BigDecimal owed = debt(account);
        if (owed.signum() == 0) {
            throw new IllegalArgumentException("账户没有欠款: " + account);
        }
        BigDecimal actual = amount.min(owed);
        transfer(when, account, BANK, actual, reason);
        BigDecimal left = owed.subtract(actual);
        if (left.signum() == 0) {
            debts.remove(account);
        } else {
            debts.put(account, left);
        }
    }

    /**
     * 日利息结算：按欠款计息并向银行支付；余额不足时剩余利息计入本金。
     *
     * @return 实际支付的利息
     */
    public synchronized BigDecimal chargeInterest(Stardate when, String account, BigDecimal dailyRate) {
        BigDecimal owed = debt(account);
        if (owed.signum() == 0 || dailyRate.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal interest = owed.multiply(dailyRate);
        BigDecimal available = accounts.getOrDefault(account, BigDecimal.ZERO);
        BigDecimal paid = interest.min(available);
        if (paid.signum() > 0) {
            transfer(when, account, BANK, paid, "贷款利息");
        }
        BigDecimal unpaid = interest.subtract(paid);
        if (unpaid.signum() > 0) {
            debts.put(account, owed.add(unpaid));
        }
        return interest;
    }

    /** 全部欠款（只读）。 */
    public synchronized Map<String, BigDecimal> debts() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(debts));
    }

    /** 流水（只读）。 */
    public synchronized List<Transaction> transactions() {
        return List.copyOf(transactions);
    }

    /** 指定账户的收入/支出汇总（用于财务报告）。 */
    public synchronized Report report(String account) {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        for (Transaction t : transactions) {
            if (t.to().equals(account)) {
                income = income.add(t.amount());
            }
            if (t.from().equals(account)) {
                expense = expense.add(t.amount());
            }
        }
        return new Report(accounts.getOrDefault(account, BigDecimal.ZERO), income, expense);
    }

    /** 财务汇总。 */
    public record Report(BigDecimal balance, BigDecimal income, BigDecimal expense) {
        public BigDecimal net() {
            return income.subtract(expense);
        }
    }
}
