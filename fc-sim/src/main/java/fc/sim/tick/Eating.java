package fc.sim.tick;

import fc.world.Agent;
import fc.world.Ids;
import fc.world.Location;
import fc.world.LocationOps;
import fc.world.Stock;
import fc.world.WorldState;

import java.math.BigDecimal;

/**
 * 进食工具：从主体所在地点消耗 1 单位口粮，同步扣减权威账本。
 */
final class Eating {

    private Eating() {
    }

    /** 是否需要进食（饥饿达到阈值）。 */
    static boolean wantsFood(Agent agent, int threshold) {
        return agent.needs().hunger() >= threshold;
    }

    /** 所在地点是否还有口粮。 */
    static boolean hasFood(WorldState state, Ids.LocationId at) {
        Location loc = state.graph().get(at);
        return loc != null && loc.stock().amount("food").compareTo(BigDecimal.ONE) >= 0;
    }

    /**
     * 尝试进食：成功则扣 1 口粮、缓解饥饿，返回 true。
     */
    static boolean tryEat(WorldState state, Agent agent, int relief) {
        Location loc = state.graph().get(agent.location());
        if (loc == null || loc.stock().amount("food").compareTo(BigDecimal.ONE) < 0) {
            return false;
        }
        Stock next = loc.stock().subtract("food", BigDecimal.ONE);
        state.graph().replace(LocationOps.withStock(loc, next));
        state.ledger().debit("food", BigDecimal.ONE);
        agent.eat(relief);
        return true;
    }
}
