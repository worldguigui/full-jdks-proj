package fc.economy;

import fc.sim.spi.MarketSystem;
import fc.sim.tick.TickContext;
import fc.world.Agent;
import fc.world.Assignment;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.MoneyLedger;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 市场与财务（阶段 9）：殖民地/设施给在岗主体发工资。
 *
 * <p>资金流：地点账户 → 居民家庭账户。地点信用点不足则**欠薪**：在该处工作的
 * 主体士气下降，久了会拒绝指令——因此玩家把货卖给殖民地（使其获得信用点）
 * 是有正反馈的。资金只在账户之间转移，账本总额不变。</p>
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
        Map<Ids.LocationId, List<Agent>> working = new TreeMap<>();
        for (Agent a : state.agents().values()) {
            if (a.assignment() == Assignment.WORKING) {
                working.computeIfAbsent(a.location(), k -> new ArrayList<>()).add(a);
            }
        }

        int unpaidPlaces = 0;
        for (Map.Entry<Ids.LocationId, List<Agent>> e : working.entrySet()) {
            int count = e.getValue().size();
            if (count == 0) {
                continue;
            }
            BigDecimal payroll = DAILY_WAGE.multiply(BigDecimal.valueOf(count));
            String from = MoneyLedger.locationAccount(e.getKey());
            if (state.money().balance(from).compareTo(payroll) >= 0) {
                state.money().transfer(state.now(), from, MoneyLedger.HOUSEHOLDS, payroll, "工资");
                for (Agent a : e.getValue()) {
                    ctx.events().enqueue(new GameEvent.WagePaid(
                            state.now(), a.location(), a.id(), DAILY_WAGE));
                }
            } else {
                unpaidPlaces++;
                for (Agent a : e.getValue()) {
                    a.perceive(0, 0, -UNPAID_MORALE_PENALTY);
                }
            }
        }
        if (unpaidPlaces > 0) {
            ctx.report("欠薪地点 " + unpaidPlaces + " 处");
        }
    }
}
