package fc.fleet;

import fc.world.Agent;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Link;
import fc.world.Location;
import fc.world.SkillSet;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * 航行服务：启航、在航查询与航程风险。
 *
 * <p>启航时校验在航状态、航路可达性、耐久与燃料是否足够，计算航程风险并写入航程记录。
 * 航程风险 = 各航段基础风险之和 + 事件风险修正 + 船型固有风险 − 最高驾驶技能 × 0.01，
 * 夹在 0..0.90 之间；事故判定在 tick 管线的航行阶段由 EVENTS 随机流完成一次。</p>
 */
public final class VoyageService {

    private VoyageService() {
    }

    /** 启航结果。 */
    public record SailResult(boolean ok, String message, int ticks, BigDecimal fuelCost,
                             double risk) {
        /** 兼容旧调用点。 */
        public SailResult(boolean ok, String message, int ticks, BigDecimal fuelCost) {
            this(ok, message, ticks, fuelCost, 0.0);
        }
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

    /** 舰上船员的最高驾驶技能（无船员为 0）。 */
    public static int pilotSkill(WorldState state, Location.Ship ship) {
        int best = 0;
        for (Agent a : state.agentsAt(ship.id())) {
            best = Math.max(best, a.skills().get(SkillSet.Skill.PILOT));
        }
        return best;
    }

    /** 航程风险（0..0.90）。 */
    public static double routeRisk(WorldState state, Location.Ship ship, Navigation.Route route) {
        double risk = ship.baseRiskFactor();
        for (Link link : route.links()) {
            risk += link.risk();
            risk += state.riskModifier(link.from(), link.to()).doubleValue();
        }
        risk -= pilotSkill(state, ship) * 0.01;
        return Math.max(0.0, Math.min(0.90, risk));
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
        if (!ship.operational()) {
            return new SailResult(false, "舰船耐久为 0，需先维修", 0, BigDecimal.ZERO);
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
        if (state.hasTech("WARP_COIL")) {
            // 高效曲率线圈：航线天数 -20%，最低 1 天。
            r = r.withTickFactor(new BigDecimal("0.8"));
        }
        BigDecimal fuel = state.goods(ship.id(), fc.world.ResourceKind.FUEL);
        if (fuel.compareTo(r.totalCost()) < 0) {
            return new SailResult(false,
                    "燃料不足：需要 " + r.totalCost().toPlainString() + "，货舱仅有 " + fuel.toPlainString(),
                    r.totalTicks(), r.totalCost());
        }
        if (r.totalCost().compareTo(ship.cargoFree()) > 0) {
            return new SailResult(false,
                    "货舱空间不足：需装载燃料 " + r.totalCost().toPlainString()
                            + "，剩余空间 " + ship.cargoFree().toPlainString(),
                    r.totalTicks(), r.totalCost());
        }

        double risk = routeRisk(state, ship, r);
        // 燃料经权威账本从舰船货舱分区扣除（世界总量减少）
        state.consume(ship.id(), fc.world.ResourceKind.FUEL, r.totalCost(), "启航燃料");
        ship.beginVoyage(new Location.VoyageProgress(from, to, 0, r.totalTicks(),
                state.now(), risk, false));
        state.chronicle().record(new GameEvent.FuelConsumed(
                state.now(), from, ship.shipId(), r.totalCost()));
        state.chronicle().record(new GameEvent.ShipDeparted(
                state.now(), from, ship.shipId(), from, to, r.totalTicks()));
        return new SailResult(true,
                "启航：" + ship.name() + " " + from.id() + " -> " + target
                        + "，预计 " + r.totalTicks() + " 天，耗油 "
                        + r.totalCost().stripTrailingZeros().toPlainString()
                        + "，风险 " + BigDecimal.valueOf(risk)
                                .setScale(2, RoundingMode.HALF_UP).toPlainString(),
                r.totalTicks(), r.totalCost(), risk);
    }

    /** 玩家所在船可飞往的地点（按知识状态过滤；UNKNOWN 不允许导航）。 */
    public static List<Ids.LocationId> navigable(WorldState state) {
        return state.locationsWithKnowledge(fc.world.KnowledgeState.DETECTED);
    }
}
