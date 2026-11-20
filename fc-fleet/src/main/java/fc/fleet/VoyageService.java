package fc.fleet;

import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Location;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 航行服务：启航、在航查询。
 *
 * <p>启航时会校验在航状态、航路可达性、燃料是否足够，并扣除燃料、写入编年史。
 * 抵达由 {@code fc-sim.tick.MovementStage} 在 tick 管线中完成。</p>
 */
public final class VoyageService {

    private VoyageService() {
    }

    /** 启航结果。 */
    public record SailResult(boolean ok, String message, int ticks, BigDecimal fuelCost) {
    }

    /** 按 ShipId 查船。 */
    public static Optional<Location.Ship> findShip(WorldState state, long shipId) {
        for (Location.Ship s : state.graph().ships()) {
            if (s.shipId().id() == shipId) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /** 按名字（精确或包含）查船。 */
    public static Optional<Location.Ship> findShipByName(WorldState state, String name) {
        for (Location.Ship s : state.graph().ships()) {
            if (s.name().equals(name)) {
                return Optional.of(s);
            }
        }
        for (Location.Ship s : state.graph().ships()) {
            if (s.name().contains(name)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /**
     * 启航：从当前位置驶向目标地点。
     *
     * @param ship   舰船
     * @param target 目标地点 id
     */
    public static SailResult sail(WorldState state, Location.Ship ship, long target) {
        if (ship.inVoyage()) {
            return new SailResult(false, "舰船在航中，无法再次启航", 0, BigDecimal.ZERO);
        }
        Ids.LocationId from = ship.parent();
        Ids.LocationId to = new Ids.LocationId(target);
        if (from.equals(to)) {
            return new SailResult(false, "已在该地点", 0, BigDecimal.ZERO);
        }
        if (!state.graph().contains(to)) {
            return new SailResult(false, "目标地点不存在: " + target, 0, BigDecimal.ZERO);
        }
        Optional<Navigation.Route> route = Navigation.find(state.graph(), from, to);
        if (route.isEmpty()) {
            return new SailResult(false, "无可用航路: " + from.id() + " -> " + target, 0, BigDecimal.ZERO);
        }
        Navigation.Route r = route.get();
        BigDecimal fuel = state.goods(ship.id(), fc.world.ResourceKind.FUEL);
        if (fuel.compareTo(r.totalCost()) < 0) {
            return new SailResult(false,
                    "燃料不足：需要 " + r.totalCost().toPlainString() + "，货舱仅有 " + fuel.toPlainString(),
                    r.totalTicks(), r.totalCost());
        }

        // 燃料经权威账本从舰船货舱分区扣除（世界总量减少）
        state.consume(ship.id(), fc.world.ResourceKind.FUEL, r.totalCost(), "启航燃料");
        ship.beginVoyage(new Location.VoyageProgress(from, to, 0, r.totalTicks(), state.now()));
        state.chronicle().record(new GameEvent.FuelConsumed(
                state.now(), from, ship.shipId(), r.totalCost()));
        state.chronicle().record(new GameEvent.ShipDeparted(
                state.now(), from, ship.shipId(), from, to, r.totalTicks()));
        return new SailResult(true,
                "启航：" + ship.name() + " " + from.id() + " -> " + target
                        + "，预计 " + r.totalTicks() + " 天，耗油 " + r.totalCost().toPlainString(),
                r.totalTicks(), r.totalCost());
    }
}
