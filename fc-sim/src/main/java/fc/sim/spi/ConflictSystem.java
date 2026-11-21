package fc.sim.spi;

import fc.sim.tick.TickContext;
import fc.world.Ids;
import fc.world.Location;
import fc.world.WorldState;

import java.math.BigDecimal;

/**
 * 冲突结算 SPI（实现位于 {@code fc-diplomacy}）。
 *
 * <p>事件阶段与玩家 {@code threaten} 命令共用同一结算器：舰队力量对威胁力量 +
 * {@code EVENTS} 流的一次随机修正，结果分为威慑/交火/赎金三类。
 * 所有损失只从现有库存扣除，不允许负库存。</p>
 */
public interface ConflictSystem {

    /** 一次冲突的结果。 */
    record Outcome(String kind, int fleetPower, int threatPower, int hullDamage,
                   BigDecimal cargoLoss, BigDecimal ransom, String detail) {
    }

    /**
     * 结算一次冲突。
     *
     * @param trigger     触发来源（piracy / threaten）
     * @param ship        玩家一侧的舰船（可为 null：威胁时以整支舰队计算）
     * @param faction     对手势力
     * @param threatBonus 额外威胁（海盗强度等）
     */
    Outcome resolve(WorldState state, TickContext ctx, String trigger,
                    Location.Ship ship, Ids.FactionId faction, int threatBonus);
}
