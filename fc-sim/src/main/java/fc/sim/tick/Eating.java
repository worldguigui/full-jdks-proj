package fc.sim.tick;

import fc.world.Agent;
import fc.world.Ids;
import fc.world.Location;
import fc.world.ResourceKind;
import fc.world.WorldState;

import java.math.BigDecimal;

/**
 * 进食工具：从主体所在地点消耗 1 单位口粮，经 {@link WorldState#tryConsume}
 * 同步权威账本与地点视图。
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
        return loc != null && state.goods(at, ResourceKind.FOOD).compareTo(BigDecimal.ONE) >= 0;
    }

    /**
     * 尝试进食：成功则扣 1 口粮、缓解饥饿，返回 true。
     */
    static boolean tryEat(WorldState state, Agent agent, int relief) {
        if (!state.tryConsume(agent.location(), ResourceKind.FOOD, BigDecimal.ONE, "口粮")) {
            return false;
        }
        agent.eat(relief);
        agent.recordAte(state.now().day());
        return true;
    }
}
