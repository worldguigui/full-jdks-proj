package fc.colony;

import fc.config.world.WorldDefinition;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Location;
import fc.world.MoneyLedger;
import fc.world.ResourceKind;
import fc.world.ShipOrder;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 船坞服务：下单建造新舰船。
 *
 * <p>船型参数来自世界配置；价格按货舱与耐久折算，另需材料与设备。
 * 订单在船坞地点由工人按天推进（工作阶段），交付时新舰船停靠船坞，
 * 不自动配备船员。若已解锁货舱框架，新舰船货舱 +50。</p>
 */
public final class ShipyardService {

    private final Map<String, WorldDefinition.ShipTypeDef> types;

    public ShipyardService(Map<String, WorldDefinition.ShipTypeDef> types) {
        this.types = Map.copyOf(types);
    }

    public record Result(boolean ok, String message) {
    }

    /** 可用船型（按 id 排序）。 */
    public List<WorldDefinition.ShipTypeDef> types() {
        return types.values().stream()
                .sorted(java.util.Comparator.comparing(WorldDefinition.ShipTypeDef::id))
                .toList();
    }

    /** 全部船坞（优先返回有工人的船坞）。 */
    public static List<Ids.LocationId> shipyards(WorldState state) {
        List<Ids.LocationId> out = new ArrayList<>();
        for (Map.Entry<Ids.LocationId, String> e : state.facilityKinds().entrySet()) {
            if ("SHIPYARD".equalsIgnoreCase(e.getValue())) {
                out.add(e.getKey());
            }
        }
        out.sort(java.util.Comparator.comparingLong(Ids.LocationId::id));
        return out;
    }

    /** 下单。 */
    public Result order(WorldState state, String typeId, String name) {
        WorldDefinition.ShipTypeDef type = types.get(typeId);
        if (type == null) {
            return new Result(false, "未知船型: " + typeId + "（可选 "
                    + String.join(", ", types.keySet()) + "）");
        }
        Ids.LocationId yard = null;
        for (Ids.LocationId id : shipyards(state)) {
            if (!state.agentsAt(id).isEmpty()) {
                yard = id;
                break;
            }
        }
        if (yard == null) {
            return new Result(false, "没有可用的船坞（需要 SHIPYARD 设施且有工人）");
        }
        BigDecimal cost = costOf(type);
        BigDecimal material = materialOf(type);
        BigDecimal equipment = equipmentOf(type);
        if (state.credits().compareTo(cost) < 0) {
            return new Result(false, "信用点不足：需要 " + cost.stripTrailingZeros().toPlainString());
        }
        if (state.goods(yard, ResourceKind.MATERIAL).compareTo(material) < 0
                || state.goods(yard, ResourceKind.EQUIPMENT).compareTo(equipment) < 0) {
            return new Result(false, "船坞材料不足：需要 "
                    + material.stripTrailingZeros().toPlainString() + " material、"
                    + equipment.stripTrailingZeros().toPlainString() + " equipment");
        }
        state.payFromPlayer(MoneyLedger.locationAccount(yard), cost, "船坞订单 " + typeId);
        state.consume(yard, ResourceKind.MATERIAL, material, "船坞建造 " + typeId);
        state.consume(yard, ResourceKind.EQUIPMENT, equipment, "船坞建造 " + typeId);
        int days = Math.max(3, type.maxHull() / 10);
        String id = state.nextOrderId();
        ShipOrder order = new ShipOrder(id, name, type.id(), yard, days, cost,
                type.cargoCapacity(), type.crewCapacity(), type.maxHull(),
                type.baseRiskFactor());
        state.addShipOrder(order);
        state.chronicle().record(new GameEvent.ShipOrdered(
                state.now(), yard, id, type.id(), days, cost));
        return new Result(true, "船坞订单 " + id + "：" + type.name() + "（" + type.id()
                + "），" + days + " 天，花费 " + cost.stripTrailingZeros().toPlainString());
    }

    /** 订单面板。 */
    public String panel(WorldState state) {
        if (state.shipOrders().isEmpty()) {
            return "当前没有船坞订单。用 order ship <船型> [名称] 下单。";
        }
        StringBuilder sb = new StringBuilder("---- 船坞订单 ----\n");
        for (ShipOrder o : state.shipOrders()) {
            sb.append("  ").append(o.id()).append(' ').append(o.name() == null
                            ? o.shipType() : o.name() + "（" + o.shipType() + "）")
                    .append("｜").append(o.status())
                    .append("｜剩余 ").append(o.remainingDays()).append('/')
                    .append(o.totalDays()).append(" 天")
                    .append("｜船坞 ").append(o.shipyard().id()).append('\n');
        }
        return sb.toString();
    }

    public static BigDecimal costOf(WorldDefinition.ShipTypeDef type) {
        BigDecimal byCargo = type.cargoCapacity().multiply(new BigDecimal("2"));
        BigDecimal byHull = BigDecimal.valueOf(type.maxHull()).multiply(new BigDecimal("5"));
        return new BigDecimal("300").add(byCargo).add(byHull)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal materialOf(WorldDefinition.ShipTypeDef type) {
        return type.cargoCapacity().divide(BigDecimal.valueOf(50), 0, RoundingMode.CEILING)
                .max(BigDecimal.ONE);
    }

    public static BigDecimal equipmentOf(WorldDefinition.ShipTypeDef type) {
        return BigDecimal.valueOf(type.maxHull())
                .divide(new BigDecimal("25"), 0, RoundingMode.CEILING).max(BigDecimal.ONE);
    }
}
