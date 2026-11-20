package fc.world;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 资源账本分区：某地点（或舰船货舱）的货物数量快照。
 *
 * <p>只表达「某一处有多少货物」，不负责守恒；守恒由 {@link ResourceLedger} 保证。
 * 信用点不属于货物，{@link #of(Map)} 会拒绝 {@link ResourceKind#CREDITS}。</p>
 *
 * <p>不可变：任何增删返回新实例。</p>
 */
public final class Stock {

    private final Map<ResourceKind, BigDecimal> amounts;

    private Stock(Map<ResourceKind, BigDecimal> amounts) {
        EnumMap<ResourceKind, BigDecimal> copy = new EnumMap<>(ResourceKind.class);
        copy.putAll(amounts);
        this.amounts = Collections.unmodifiableMap(copy);
    }

    /** 空库存。 */
    public static Stock empty() {
        return new Stock(Map.of());
    }

    /** 从资源到数量的映射构造；数量必须非负，且不得含信用点。 */
    public static Stock of(Map<ResourceKind, BigDecimal> amounts) {
        Map<ResourceKind, BigDecimal> copy = new EnumMap<>(ResourceKind.class);
        amounts.forEach((k, v) -> {
            if (k == null) {
                throw new IllegalArgumentException("资源种类不得为 null");
            }
            if (k.isLedgerOnly()) {
                throw new IllegalArgumentException("信用点只能进入财务账本，不能进入库存: " + k.id());
            }
            if (v == null || v.signum() < 0) {
                throw new IllegalArgumentException("资源数量不得为负: " + k.id() + "=" + v);
            }
            if (v.signum() > 0) {
                copy.put(k, v);
            }
        });
        return new Stock(copy);
    }

    /** 查询某货物数量（缺省为 0）。 */
    public BigDecimal amount(ResourceKind kind) {
        return amounts.getOrDefault(kind, BigDecimal.ZERO);
    }

    /** 按稳定标识查询货物数量。 */
    public BigDecimal amount(String resource) {
        return amount(ResourceKind.goodsFromId(resource));
    }

    /** 是否包含某货物（数量 > 0）。 */
    public boolean contains(ResourceKind kind) {
        BigDecimal a = amounts.get(kind);
        return a != null && a.signum() > 0;
    }

    /** 是否包含某货物。 */
    public boolean contains(String resource) {
        return contains(ResourceKind.goodsFromId(resource));
    }

    /** 全部货物（不可变视图）。 */
    public Map<ResourceKind, BigDecimal> amounts() {
        return amounts;
    }

    /** 增加某货物数量，返回新库存。 */
    public Stock add(ResourceKind kind, BigDecimal delta) {
        if (kind.isLedgerOnly()) {
            throw new IllegalArgumentException("信用点只能进入财务账本: " + kind.id());
        }
        BigDecimal cur = amounts.getOrDefault(kind, BigDecimal.ZERO);
        BigDecimal next = cur.add(delta);
        if (next.signum() < 0) {
            throw new IllegalArgumentException("资源不足: " + kind.id() + " 需 " + delta.negate()
                    + " 仅有 " + cur);
        }
        Map<ResourceKind, BigDecimal> copy = new EnumMap<>(ResourceKind.class);
        copy.putAll(amounts);
        if (next.signum() == 0) {
            copy.remove(kind);
        } else {
            copy.put(kind, next);
        }
        return new Stock(copy);
    }

    /** 增加某货物数量（按标识）。 */
    public Stock add(String resource, BigDecimal delta) {
        return add(ResourceKind.goodsFromId(resource), delta);
    }

    /** 扣除某货物数量，不足则抛异常。 */
    public Stock subtract(ResourceKind kind, BigDecimal delta) {
        return add(kind, delta.negate());
    }

    /** 扣除某货物数量（按标识）。 */
    public Stock subtract(String resource, BigDecimal delta) {
        return add(ResourceKind.goodsFromId(resource), delta);
    }

    /** 是否为空。 */
    public boolean isEmpty() {
        return amounts.isEmpty();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<ResourceKind, BigDecimal> e : amounts.entrySet()) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(e.getKey().id()).append('=').append(e.getValue().stripTrailingZeros().toPlainString());
        }
        return sb.append('}').toString();
    }
}
