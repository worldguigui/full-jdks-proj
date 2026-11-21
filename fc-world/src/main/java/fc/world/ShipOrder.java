package fc.world;

import fc.world.Ids.LocationId;

import java.math.BigDecimal;

/**
 * 船坞订单：付费后按天建造一艘指定船型的新舰船。
 *
 * <p>建造天数、船型参数与价格来自配置的船型表；完成后新舰船停靠在船坞地点。
 * 订单状态进入状态哈希。</p>
 */
public final class ShipOrder {

    public enum Status {
        BUILDING, DELIVERED
    }

    private final String id;
    private final String name;
    private final String shipType;
    private final LocationId shipyard;
    private final int totalDays;
    private final BigDecimal cost;
    private final BigDecimal cargoCapacity;
    private final int crewCapacity;
    private final int maxHull;
    private final double baseRiskFactor;
    private int remainingDays;
    private Status status = Status.BUILDING;

    public ShipOrder(String id, String shipType, LocationId shipyard, int totalDays,
                     BigDecimal cost, BigDecimal cargoCapacity, int crewCapacity,
                     int maxHull, double baseRiskFactor) {
        this(id, null, shipType, shipyard, totalDays, cost, cargoCapacity, crewCapacity,
                maxHull, baseRiskFactor);
    }

    public ShipOrder(String id, String name, String shipType, LocationId shipyard, int totalDays,
                     BigDecimal cost, BigDecimal cargoCapacity, int crewCapacity,
                     int maxHull, double baseRiskFactor) {
        this.id = id;
        this.name = name == null || name.isBlank() ? null : name;
        this.shipType = shipType;
        this.shipyard = shipyard;
        this.totalDays = Math.max(1, totalDays);
        this.remainingDays = this.totalDays;
        this.cost = cost;
        this.cargoCapacity = cargoCapacity;
        this.crewCapacity = crewCapacity;
        this.maxHull = maxHull;
        this.baseRiskFactor = baseRiskFactor;
    }

    public String id() {
        return id;
    }

    /** 玩家指定的新舰船名称（可为 null，交付时自动命名）。 */
    public String name() {
        return name;
    }

    public String shipType() {
        return shipType;
    }

    public LocationId shipyard() {
        return shipyard;
    }

    public int totalDays() {
        return totalDays;
    }

    public int remainingDays() {
        return remainingDays;
    }

    public BigDecimal cost() {
        return cost;
    }

    public BigDecimal cargoCapacity() {
        return cargoCapacity;
    }

    public int crewCapacity() {
        return crewCapacity;
    }

    public int maxHull() {
        return maxHull;
    }

    public double baseRiskFactor() {
        return baseRiskFactor;
    }

    public Status status() {
        return status;
    }

    public boolean advanceDay() {
        if (remainingDays > 0) {
            remainingDays--;
        }
        return remainingDays == 0;
    }

    public void deliver() {
        status = Status.DELIVERED;
    }
}
