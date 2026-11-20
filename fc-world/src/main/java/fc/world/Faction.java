package fc.world;

import fc.world.Ids.FactionId;
import fc.world.Ids.LocationId;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 势力：拥有目标、财政与声誉的长期行为者。
 *
 * <p>势力不拥有独立的货物账本；其资产体现为所在地点的库存与
 * {@link MoneyLedger} 中的势力账户。势力 AI 由 {@code fc-diplomacy} 实现，
 * 本类只承载配置与状态。</p>
 */
public final class Faction {

    /** 势力性格/目标类型。 */
    public enum Kind {
        TRADE, COLONY, MILITARY, SCIENCE
    }

    private final FactionId id;
    private final String name;
    private final Kind kind;
    private final LocationId seat;
    private final String doctrine;
    private final ResourceKind priorityGood;
    private final BigDecimal reserveTarget;
    private final BigDecimal stockTarget;
    private final BigDecimal tradeUnitPrice;
    private BigDecimal taxRate;

    public Faction(FactionId id, String name, Kind kind, LocationId seat,
                   String doctrine, ResourceKind priorityGood,
                   BigDecimal reserveTarget, BigDecimal stockTarget,
                   BigDecimal tradeUnitPrice, BigDecimal taxRate) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        this.kind = Objects.requireNonNull(kind);
        this.seat = Objects.requireNonNull(seat);
        this.doctrine = doctrine == null ? "" : doctrine;
        this.priorityGood = Objects.requireNonNull(priorityGood);
        this.reserveTarget = Objects.requireNonNull(reserveTarget);
        this.stockTarget = Objects.requireNonNull(stockTarget);
        this.tradeUnitPrice = Objects.requireNonNull(tradeUnitPrice);
        this.taxRate = Objects.requireNonNull(taxRate);
    }

    public FactionId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Kind kind() {
        return kind;
    }

    /** 势力中心（其库存与事件落点）。 */
    public LocationId seat() {
        return seat;
    }

    /** 行为倾向说明（配置文本）。 */
    public String doctrine() {
        return doctrine;
    }

    /** 势力最关心的货物（粮食/燃料/设备等）。 */
    public ResourceKind priorityGood() {
        return priorityGood;
    }

    /** 希望保有的财政储备。 */
    public BigDecimal reserveTarget() {
        return reserveTarget;
    }

    /** 希望保有的优先货物库存。 */
    public BigDecimal stockTarget() {
        return stockTarget;
    }

    /** 势力采购单价（配置值，避免势力 AI 依赖市场模块）。 */
    public BigDecimal tradeUnitPrice() {
        return tradeUnitPrice;
    }

    /** 势力对本地交易的税率。 */
    public BigDecimal taxRate() {
        return taxRate;
    }

    public void setTaxRate(BigDecimal next) {
        if (next.signum() < 0) {
            throw new IllegalArgumentException("税率不得为负");
        }
        this.taxRate = next;
    }

    /** 财务账户名。 */
    public String account() {
        return MoneyLedger.factionAccount(id);
    }
}
