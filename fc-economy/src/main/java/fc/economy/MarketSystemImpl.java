package fc.economy;

import fc.sim.spi.MarketSystem;
import fc.sim.tick.TickContext;
import fc.world.Agent;
import fc.world.Assignment;
import fc.world.Ids;
import fc.world.Location;
import fc.world.LocationOps;
import fc.world.Stock;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 市场与财务（阶段 9）：殖民地/设施给在岗主体发工资。
 *
 * <p>资金流：地点信用点 → 工资支出（记账本扣减，视为生活消费离开被追踪经济体）。
 * 地点信用点不足则**欠薪**：在该处工作的主体士气下降，久了会拒绝指令——
 * 因此玩家把货卖给殖民地（使其获得信用点）是有正反馈的。</p>
 *
 * <p>价格本身不需要在本阶段写入状态：{@link PriceModel} 按库存现算，
 * 存档只需保存库存与信用点。</p>
 */
public final class MarketSystemImpl implements MarketSystem {

    /** 每人每日工资。 */
    public static final BigDecimal DAILY_WAGE = BigDecimal.ONE;
    /** 欠薪时士气惩罚。 */
    private static final int UNPAID_MORALE_PENALTY = 1;

    @Override
    public void onTick(WorldState state, TickContext ctx) {
        // 统计各地点在岗人数（按地点 id 有序遍历，保证确定性）
        Map<Ids.LocationId, Integer> working = new HashMap<>();
        Map<Ids.LocationId, java.util.List<Agent>> byLocation = new HashMap<>();
        for (Agent a : state.agents().values()) {
            if (a.assignment() != Assignment.WORKING) {
                continue;
            }
            working.merge(a.location(), 1, Integer::sum);
            byLocation.computeIfAbsent(a.location(), k -> new java.util.ArrayList<>()).add(a);
        }

        int unpaidPlaces = 0;
        for (Map.Entry<Ids.LocationId, Integer> e : working.entrySet()) {
            Location loc = state.graph().get(e.getKey());
            if (loc == null) {
                continue;
            }
            BigDecimal payroll = DAILY_WAGE.multiply(BigDecimal.valueOf(e.getValue()));
            BigDecimal credits = loc.stock().amount("credits");
            if (credits.compareTo(payroll) >= 0) {
                Stock next = loc.stock().subtract("credits", payroll);
                state.graph().replace(LocationOps.withStock(loc, next));
                state.ledger().debit("credits", payroll);
            } else {
                unpaidPlaces++;
                for (Agent a : byLocation.get(e.getKey())) {
                    a.perceive(0, 0, -UNPAID_MORALE_PENALTY);
                }
            }
        }
        if (unpaidPlaces > 0) {
            ctx.report("欠薪地点 " + unpaidPlaces + " 处");
        }
    }
}
