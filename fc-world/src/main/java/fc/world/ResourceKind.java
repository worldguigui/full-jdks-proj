package fc.world;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 资源种类：11 种货物 + 信用点。
 *
 * <p>货物进入物理库存（{@link Stock}）与守恒账本（{@link ResourceLedger}）；
 * 信用点只进入财务账本（{@link MoneyLedger}），不得写入 {@code Stock}。
 * 配置中的资源名必须是本枚举的 {@link #id()}，装载时校验。</p>
 */
public enum ResourceKind {
    FUEL("fuel", "燃料", false),
    ENERGY("energy", "能源", false),
    FOOD("food", "食物", false),
    WATER("water", "水", false),
    ORE("ore", "矿物", false),
    METAL("metal", "金属", false),
    MATERIAL("material", "材料", false),
    EQUIPMENT("equipment", "设备", false),
    MEDICINE("medicine", "药品", false),
    LUXURY("luxury", "奢侈品", false),
    RESEARCH("research", "科研数据", false),
    CREDITS("credits", "信用点", true);

    /** 全部货物（不含信用点）。 */
    public static final List<ResourceKind> GOODS = List.of(
            FUEL, ENERGY, FOOD, WATER, ORE, METAL,
            MATERIAL, EQUIPMENT, MEDICINE, LUXURY, RESEARCH);

    private final String id;
    private final String displayName;
    private final boolean ledgerOnly;

    ResourceKind(String id, String displayName, boolean ledgerOnly) {
        this.id = id;
        this.displayName = displayName;
        this.ledgerOnly = ledgerOnly;
    }

    /** 配置/存档中使用的稳定标识。 */
    public String id() {
        return id;
    }

    /** 展示名（中文）。 */
    public String displayName() {
        return displayName;
    }

    /** 是否仅存在于财务账本（不进入物理库存）。 */
    public boolean isLedgerOnly() {
        return ledgerOnly;
    }

    /** 按稳定标识解析；未知名称抛出带候选值的异常。 */
    public static ResourceKind fromId(String id) {
        Objects.requireNonNull(id, "id");
        for (ResourceKind kind : values()) {
            if (kind.id.equals(id)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("未知资源: " + id + "（可选 " + GOODS + ", " + CREDITS + "）");
    }

    /** 解析货物；信用点不是货物。 */
    public static ResourceKind goodsFromId(String id) {
        ResourceKind kind = fromId(id);
        if (kind.ledgerOnly) {
            throw new IllegalArgumentException("信用点不能作为物理货物: " + id);
        }
        return kind;
    }

    /** 资源清单：一揽子资源及其数量（值对象，不可变）。 */
    public record Lot(BigDecimal amount, ResourceKind kind) {
        public Lot {
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(kind, "kind");
            if (amount.signum() < 0) {
                throw new IllegalArgumentException("资源数量不得为负: " + amount);
            }
        }
    }
}
