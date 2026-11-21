package fc.fleet;

import fc.world.Agent;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Location;
import fc.world.MoneyLedger;
import fc.world.ResourceKind;
import fc.world.SkillSet;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 舰船维修服务。
 *
 * <p>舰船必须停泊；维修能力来自船坞设施，或地点上工程技能 ≥ 3 的人员
 * （含舰上船员）。每次维修按 {@code 5 + 最高工程技能 + 船坞加成 5} 点计算，
 * 消耗船坞/地点每 10 点 1 单位材料，并按 3 信用点/点支付给该地点账户。
 * 耐久只恢复到上限，不凭空产生材料或资金。</p>
 */
public final class MaintenanceService {

    public static final int YARD_BONUS = 5;
    public static final BigDecimal CREDITS_PER_POINT = new BigDecimal("3");
    public static final int MIN_ENGINEERING = 3;

    private MaintenanceService() {
    }

    public record Result(boolean ok, String message) {
    }

    public static Result repair(WorldState state, Location.Ship ship) {
        if (ship.inVoyage()) {
            return new Result(false, "舰船在航中，无法维修");
        }
        int missing = ship.maxHull() - ship.hull();
        if (missing <= 0) {
            return new Result(false, ship.name() + " 耐久已满（" + ship.hull()
                    + "/" + ship.maxHull() + "）");
        }
        Ids.LocationId dock = ship.parent();
        Location base = state.graph().get(dock);
        if (base == null) {
            return new Result(false, "舰船停泊地点不存在: " + dock.id());
        }
        boolean yard = "SHIPYARD".equalsIgnoreCase(state.facilityKind(dock));
        int engineering = 0;
        for (Agent a : state.agentsAt(ship.id())) {
            engineering = Math.max(engineering, a.skills().get(SkillSet.Skill.ENGINEERING));
        }
        for (Agent a : state.agentsAt(dock)) {
            engineering = Math.max(engineering, a.skills().get(SkillSet.Skill.ENGINEERING));
        }
        if (!yard && engineering < MIN_ENGINEERING) {
            return new Result(false, base.name() + " 没有维修能力（需要船坞或工程技能 ≥ "
                    + MIN_ENGINEERING + " 的人员）");
        }
        int points = Math.min(missing, 5 + engineering + (yard ? YARD_BONUS : 0));
        BigDecimal material = BigDecimal.valueOf(points)
                .divide(BigDecimal.TEN, 0, RoundingMode.CEILING).max(BigDecimal.ONE);
        BigDecimal cost = BigDecimal.valueOf(points).multiply(CREDITS_PER_POINT);
        if (state.goods(dock, ResourceKind.MATERIAL).compareTo(material) < 0) {
            return new Result(false, "维修材料不足：需要 "
                    + material.stripTrailingZeros().toPlainString() + " material，"
                    + base.name() + " 仅有 " + state.goods(dock, ResourceKind.MATERIAL)
                            .stripTrailingZeros().toPlainString());
        }
        if (state.credits().compareTo(cost) < 0) {
            return new Result(false, "信用点不足：维修需要 "
                    + cost.stripTrailingZeros().toPlainString());
        }
        state.payFromPlayer(MoneyLedger.locationAccount(dock), cost, "舰船维修 " + ship.name());
        state.consume(dock, ResourceKind.MATERIAL, material, "舰船维修 " + ship.name());
        int actual = ship.repair(points);
        for (Agent a : state.agentsAt(ship.id())) {
            if (a.assignment() == fc.world.Assignment.IDLE) {
                a.assign(fc.world.Assignment.WORKING);
            }
        }
        state.chronicle().record(new GameEvent.ShipRepaired(
                state.now(), dock, ship.shipId(), actual, ship.hull()));
        return new Result(true, "维修 " + ship.name() + "：耐久 +" + actual
                + "（" + ship.hull() + "/" + ship.maxHull() + "），消耗 "
                + material.stripTrailingZeros().toPlainString() + " material、"
                + cost.stripTrailingZeros().toPlainString() + " 信用点");
    }
}
