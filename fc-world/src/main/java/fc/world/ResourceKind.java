package fc.world;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 资源种类。
 *
 * <p>初始世界有 11 种货物 + credits（信用点仅记账本，不进入物理库存）。
 * 具体货物清单由配置（{@code fc-config}）定义，本枚举只声明「货物 vs 记账本」的语义区分。</p>
 */
public enum ResourceKind {
    /** 信用点：仅记账本，不进入物理库存。 */
    CREDITS(true),
    /** 通用货物占位（具体货物由配置展开，见 ResourceCatalog）。 */
    CARGO(false);

    private final boolean ledgerOnly;

    ResourceKind(boolean ledgerOnly) {
        this.ledgerOnly = ledgerOnly;
    }

    /** 是否仅存在于记账本（不进入物理库存）。 */
    public boolean isLedgerOnly() {
        return ledgerOnly;
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
