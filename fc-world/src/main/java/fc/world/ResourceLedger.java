package fc.world;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 权威守恒账本（ResourceLedger）。
 *
 * <p>整个世界的资源总量记账。{@link Stock} 是它的分区视图（按地点/账本分区）。
 * 守恒约束（总量不凭空增减）由 {@code fc-economy} 的结算在写入本账本时保证。</p>
 *
 * <p>线程安全：以 synchronized 保证并发结算下的原子性（M2 并发 tick 会用到）。</p>
 */
public final class ResourceLedger {

    private final Map<String, BigDecimal> totals;

    public ResourceLedger() {
        this.totals = new HashMap<>();
    }

    /** 查询全账本的某资源总量。 */
    public synchronized BigDecimal total(String resource) {
        return totals.getOrDefault(resource, BigDecimal.ZERO);
    }

    /** 记账（增加总量）。 */
    public synchronized void credit(String resource, BigDecimal amount) {
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("credit 金额不得为负: " + amount);
        }
        totals.merge(resource, amount, BigDecimal::add);
    }

    /** 记账（减少总量，不足则抛异常）。 */
    public synchronized void debit(String resource, BigDecimal amount) {
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("debit 金额不得为负: " + amount);
        }
        BigDecimal cur = totals.getOrDefault(resource, BigDecimal.ZERO);
        if (cur.compareTo(amount) < 0) {
            throw new IllegalArgumentException("账本不足: " + resource + " 需 " + amount + " 仅有 " + cur);
        }
        BigDecimal next = cur.subtract(amount);
        if (next.signum() == 0) {
            totals.remove(resource);
        } else {
            totals.put(resource, next);
        }
    }

    /** 全部资源总量（只读视图）。 */
    public synchronized Map<String, BigDecimal> totals() {
        return Collections.unmodifiableMap(new HashMap<>(totals));
    }
}
