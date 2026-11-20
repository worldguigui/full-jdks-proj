package fc.world;

import fc.world.Ids.LocationId;
import fc.world.time.Stardate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权威货物账本。
 *
 * <p>每个地点（包括舰船这个移动地点）有一份账本分区；{@link Stock} 是分区的
 * 不可变快照。所有库存变化都必须经过本类，以便同时维护分区、总量与审计流水。
 * 跨地点运输通过「在途分区」记账：货物离开来源后仍在账本中，抵达后进入目标分区，
 * 因此运输前后总量守恒。</p>
 *
 * <p>生产产出是唯一允许增加货物总量的来源；消耗是唯一允许减少总量的去处。
 * {@link #verify(Map)} 用于在测试/诊断中核对地点快照与权威账本是否一致。</p>
 */
public final class ResourceLedger {

    /** 一笔账本流水。 */
    public record Entry(Stardate when, String from, String to, ResourceKind resource,
                        BigDecimal amount, String reason) {
    }

    private final Map<LocationId, EnumMap<ResourceKind, BigDecimal>> accounts = new LinkedHashMap<>();
    private final Map<Long, EnumMap<ResourceKind, BigDecimal>> transit = new LinkedHashMap<>();
    private final List<Entry> entries = new ArrayList<>();

    /** 开立地点分区（装配期）。 */
    public synchronized void open(LocationId id, Stock initial) {
        EnumMap<ResourceKind, BigDecimal> copy = new EnumMap<>(ResourceKind.class);
        copy.putAll(initial.amounts());
        accounts.put(id, copy);
    }

    /** 某地点分区快照。 */
    public synchronized Stock stock(LocationId id) {
        return Stock.of(accounts.getOrDefault(id, new EnumMap<>(ResourceKind.class)));
    }

    /** 全部地点分区（调试/校验用）。 */
    public synchronized Map<LocationId, Stock> accounts() {
        Map<LocationId, Stock> out = new LinkedHashMap<>();
        for (Map.Entry<LocationId, EnumMap<ResourceKind, BigDecimal>> e : accounts.entrySet()) {
            out.put(e.getKey(), Stock.of(e.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * 用完整快照覆盖某分区（只用于受控的世界状态同步）。
     *
     * @return 实际发生的净变化（正数为增加、负数为减少）
     */
    public synchronized Map<ResourceKind, BigDecimal> set(LocationId id, Stock next,
                                                           Stardate when, String reason) {
        EnumMap<ResourceKind, BigDecimal> account =
                accounts.computeIfAbsent(id, k -> new EnumMap<>(ResourceKind.class));
        Map<ResourceKind, BigDecimal> delta = new EnumMap<>(ResourceKind.class);
        for (ResourceKind kind : ResourceKind.GOODS) {
            BigDecimal before = account.getOrDefault(kind, BigDecimal.ZERO);
            BigDecimal after = next.amount(kind);
            if (before.compareTo(after) == 0) {
                continue;
            }
            delta.put(kind, after.subtract(before));
            if (after.signum() == 0) {
                account.remove(kind);
            } else {
                account.put(kind, after);
            }
            entries.add(new Entry(when, id.toString(), id.toString(), kind,
                    after.subtract(before), reason));
        }
        return delta;
    }

    /** 消耗货物（总量减少）。 */
    public synchronized void consume(LocationId id, ResourceKind kind, BigDecimal amount,
                                     Stardate when, String reason) {
        requirePositive(amount);
        Stock cur = stock(id);
        set(id, cur.subtract(kind, amount), when, reason + "（消耗）");
    }

    /** 生产货物（总量增加）。 */
    public synchronized void produce(LocationId id, ResourceKind kind, BigDecimal amount,
                                     Stardate when, String reason) {
        requirePositive(amount);
        Stock cur = stock(id);
        set(id, cur.add(kind, amount), when, reason + "（产出）");
    }

    /** 同地点/跨地点转移（总量不变）。 */
    public synchronized void transfer(LocationId from, LocationId to, ResourceKind kind,
                                      BigDecimal amount, Stardate when, String reason) {
        requirePositive(amount);
        if (from.equals(to)) {
            return;
        }
        BigDecimal before = total(kind);
        set(from, stock(from).subtract(kind, amount), when, reason + "（转出）");
        set(to, stock(to).add(kind, amount), when, reason + "（转入）");
        assertUnchanged(kind, before, "转移 " + from + "→" + to);
    }

    /** 把货物放入在途分区（仍是世界总量的一部分）。 */
    public synchronized void holdForTransfer(long transferId, LocationId from, ResourceKind kind,
                                             BigDecimal amount, Stardate when, String reason) {
        requirePositive(amount);
        BigDecimal before = total(kind);
        set(from, stock(from).subtract(kind, amount), when, reason + "（交付运输）");
        EnumMap<ResourceKind, BigDecimal> load =
                transit.computeIfAbsent(transferId, k -> new EnumMap<>(ResourceKind.class));
        load.merge(kind, amount, BigDecimal::add);
        assertUnchanged(kind, before, "在途装载 " + transferId);
    }

    /** 在途货物抵达目标分区。 */
    public synchronized void deliverTransfer(long transferId, LocationId to, ResourceKind kind,
                                             BigDecimal amount, Stardate when, String reason) {
        requirePositive(amount);
        EnumMap<ResourceKind, BigDecimal> load = transit.get(transferId);
        BigDecimal inTransit = load == null ? BigDecimal.ZERO
                : load.getOrDefault(kind, BigDecimal.ZERO);
        if (inTransit.compareTo(amount) < 0) {
            throw new IllegalStateException("在途货物不足: transfer=" + transferId
                    + " kind=" + kind.id() + " 需 " + amount + " 仅有 " + inTransit);
        }
        BigDecimal before = total(kind);
        if (inTransit.compareTo(amount) == 0) {
            load.remove(kind);
            if (load.isEmpty()) {
                transit.remove(transferId);
            }
        } else {
            load.put(kind, inTransit.subtract(amount));
        }
        set(to, stock(to).add(kind, amount), when, reason + "（抵达）");
        assertUnchanged(kind, before, "在途交付 " + transferId);
    }

    /** 返回在途货物（取消运输）。 */
    public synchronized void cancelTransfer(long transferId, LocationId from, ResourceKind kind,
                                            BigDecimal amount, Stardate when, String reason) {
        requirePositive(amount);
        EnumMap<ResourceKind, BigDecimal> load = transit.get(transferId);
        BigDecimal inTransit = load == null ? BigDecimal.ZERO
                : load.getOrDefault(kind, BigDecimal.ZERO);
        if (inTransit.compareTo(amount) < 0) {
            throw new IllegalStateException("在途货物不足，无法取消: transfer=" + transferId);
        }
        BigDecimal before = total(kind);
        if (inTransit.compareTo(amount) == 0) {
            load.remove(kind);
            if (load.isEmpty()) {
                transit.remove(transferId);
            }
        } else {
            load.put(kind, inTransit.subtract(amount));
        }
        set(from, stock(from).add(kind, amount), when, reason + "（退回）");
        assertUnchanged(kind, before, "取消运输 " + transferId);
    }

    /** 某货物世界总量（分区 + 在途）。 */
    public synchronized BigDecimal total(ResourceKind kind) {
        BigDecimal sum = BigDecimal.ZERO;
        for (EnumMap<ResourceKind, BigDecimal> a : accounts.values()) {
            sum = sum.add(a.getOrDefault(kind, BigDecimal.ZERO));
        }
        for (EnumMap<ResourceKind, BigDecimal> a : transit.values()) {
            sum = sum.add(a.getOrDefault(kind, BigDecimal.ZERO));
        }
        return sum;
    }

    /** 全部货物的世界总量。 */
    public synchronized Map<ResourceKind, BigDecimal> totals() {
        EnumMap<ResourceKind, BigDecimal> out = new EnumMap<>(ResourceKind.class);
        for (ResourceKind kind : ResourceKind.GOODS) {
            out.put(kind, total(kind));
        }
        return Collections.unmodifiableMap(out);
    }

    /** 在途货物快照（诊断用）。 */
    public synchronized Stock inTransit(long transferId) {
        return Stock.of(transit.getOrDefault(transferId, new EnumMap<>(ResourceKind.class)));
    }

    /** 审计流水（只读）。 */
    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    /**
     * 校对给定地点快照与权威账本是否一致；不一致即抛异常。
     * 这是 M3「Transfer 守恒」与各阶段结算的最终防线。
     */
    public synchronized void verify(Map<LocationId, Stock> snapshots) {
        for (Map.Entry<LocationId, Stock> e : snapshots.entrySet()) {
            Stock account = stock(e.getKey());
            for (ResourceKind kind : ResourceKind.GOODS) {
                if (account.amount(kind).compareTo(e.getValue().amount(kind)) != 0) {
                    throw new IllegalStateException("账本与地点库存不一致: " + e.getKey()
                            + " " + kind.id() + " 账本=" + account.amount(kind)
                            + " 库存=" + e.getValue().amount(kind));
                }
            }
        }
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("数量必须为正: " + amount);
        }
    }

    private void assertUnchanged(ResourceKind kind, BigDecimal before, String action) {
        BigDecimal after = total(kind);
        if (before.compareTo(after) != 0) {
            throw new IllegalStateException("守恒断言失败: " + action + " " + kind.id()
                    + " 前=" + before + " 后=" + after);
        }
    }
}
