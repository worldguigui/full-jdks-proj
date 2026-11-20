package fc.world;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 资源账本（Stock）：某地点/账本分区的资源数量快照。
 *
 * <p>这是「权威守恒账本」{@link ResourceLedger} 的分区视图。本类型只表达
 * 「某一处有多少资源」，不负责守恒约束——守恒由 {@code fc-economy} 的结算保证。</p>
 *
 * <p>不可变：任何增删返回新实例。</p>
 */
public final class Stock {

    private final Map<String, BigDecimal> amounts;

    private Stock(Map<String, BigDecimal> amounts) {
        this.amounts = Collections.unmodifiableMap(new HashMap<>(amounts));
    }

    /** 空账本。 */
    public static Stock empty() {
        return new Stock(Map.of());
    }

    /** 从资源名到数量的映射构造。 */
    public static Stock of(Map<String, BigDecimal> amounts) {
        Map<String, BigDecimal> copy = new HashMap<>();
        amounts.forEach((k, v) -> {
            if (v.signum() < 0) {
                throw new IllegalArgumentException("资源数量不得为负: " + k + "=" + v);
            }
            copy.put(k, v);
        });
        return new Stock(copy);
    }

    /** 查询某资源的数量（缺省为 0）。 */
    public BigDecimal amount(String resource) {
        return amounts.getOrDefault(resource, BigDecimal.ZERO);
    }

    /** 是否包含某资源（数量 > 0）。 */
    public boolean contains(String resource) {
        BigDecimal a = amounts.get(resource);
        return a != null && a.signum() > 0;
    }

    /** 全部资源名（不可变视图）。 */
    public Map<String, BigDecimal> amounts() {
        return amounts;
    }

    /** 增加某资源数量，返回新账本。 */
    public Stock add(String resource, BigDecimal delta) {
        Map<String, BigDecimal> copy = new HashMap<>(amounts);
        BigDecimal next = copy.getOrDefault(resource, BigDecimal.ZERO).add(delta);
        if (next.signum() < 0) {
            throw new IllegalArgumentException("资源不足: " + resource + " 需 " + delta.negate()
                    + " 仅有 " + copy.getOrDefault(resource, BigDecimal.ZERO));
        }
        if (next.signum() == 0) {
            copy.remove(resource);
        } else {
            copy.put(resource, next);
        }
        return new Stock(copy);
    }

    /** 扣除某资源数量，返回新账本（不足则抛异常）。 */
    public Stock subtract(String resource, BigDecimal delta) {
        return add(resource, delta.negate());
    }

    @Override
    public String toString() {
        return amounts.toString();
    }
}
