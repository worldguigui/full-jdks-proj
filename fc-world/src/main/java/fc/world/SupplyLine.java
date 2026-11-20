package fc.world;

import fc.world.Ids.LocationId;
import fc.world.Ids.SupplyLineId;

import java.math.BigDecimal;

/**
 * 补给线：按固定间隔从一处向另一处调运货物。
 *
 * <p>D0 要求建立第一条稳定补给线，D1 要求第一条贸易线。补给线只描述
 * 「谁在何时运多少」，实际运输仍由 {@link Transfer} 在途结算。</p>
 */
public final class SupplyLine {

    private final SupplyLineId id;
    private final LocationId from;
    private final LocationId to;
    private final ResourceKind resource;
    private final BigDecimal amountPerRun;
    private final int intervalDays;
    private final int travelTicks;
    private int daysUntilNext;
    private boolean active;
    private int deliveredRuns;

    public SupplyLine(SupplyLineId id, LocationId from, LocationId to, ResourceKind resource,
                      BigDecimal amountPerRun, int intervalDays, int travelTicks) {
        if (amountPerRun.signum() <= 0) {
            throw new IllegalArgumentException("补给量必须为正: " + amountPerRun);
        }
        if (intervalDays < 1) {
            throw new IllegalArgumentException("补给间隔必须 ≥ 1 天");
        }
        if (travelTicks < 1) {
            throw new IllegalArgumentException("运输天数必须 ≥ 1");
        }
        this.id = id;
        this.from = from;
        this.to = to;
        this.resource = resource;
        this.amountPerRun = amountPerRun;
        this.intervalDays = intervalDays;
        this.travelTicks = travelTicks;
        // 首班车在建立后的下一天发出，此后每 intervalDays 天一班
        this.daysUntilNext = 1;
        this.active = true;
    }

    public SupplyLineId id() {
        return id;
    }

    public LocationId from() {
        return from;
    }

    public LocationId to() {
        return to;
    }

    public ResourceKind resource() {
        return resource;
    }

    public BigDecimal amountPerRun() {
        return amountPerRun;
    }

    public int intervalDays() {
        return intervalDays;
    }

    /** 每批货物在途天数（建立补给线时按航线计算）。 */
    public int travelTicks() {
        return travelTicks;
    }

    public boolean active() {
        return active;
    }

    public int deliveredRuns() {
        return deliveredRuns;
    }

    /** 距离下次发车的天数（用于状态哈希与诊断）。 */
    public int daysUntilNext() {
        return daysUntilNext;
    }

    public void deactivate() {
        this.active = false;
    }

    /** 到达发车日则返回 true 并重置计数；否则递减并返回 false。 */
    public boolean due() {
        if (!active) {
            return false;
        }
        daysUntilNext--;
        if (daysUntilNext <= 0) {
            daysUntilNext = intervalDays;
            return true;
        }
        return false;
    }

    public void countDelivery() {
        deliveredRuns++;
    }
}
