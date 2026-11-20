package fc.sim.tick;

import fc.world.Agent;
import fc.world.Assignment;
import fc.world.Trait;
import fc.world.WorldState;

/**
 * 阶段 4：主体感知（刷新需求与状态）。
 *
 * <p>统一决策循环的第一步：所有主体（船员/居民/使者）按性格与当前指派刷新
 * 饥饿、疲劳、士气。这里不做决策，只更新"此刻感受如何"。</p>
 */
public final class PerceiveStage implements TickStage {

    @Override
    public String name() {
        return "4-perceive";
    }

    @Override
    public void apply(WorldState state, TickContext ctx) {
        boolean harsh = ctx.weather() >= 85;
        for (Agent a : state.agents().values()) {
            int hunger = switch (a.trait()) {
                case STOIC -> 1;
                case ANXIOUS -> 3;
                default -> 2;
            };
            int fatigue;
            if (a.assignment() == Assignment.WORKING) {
                fatigue = switch (a.trait()) {
                    case DILIGENT -> 5;
                    case LAZY -> 8;
                    default -> 6;
                };
            } else if (a.assignment() == Assignment.RESTING) {
                fatigue = -12;
            } else {
                fatigue = 1;
            }
            int morale = 0;
            if (harsh) {
                morale -= (a.trait() == Trait.STOIC) ? 0 : 1;
            }
            if (a.needs().hunger() >= 60) {
                morale -= (a.trait() == Trait.STOIC) ? 0 : 1;
            }
            if (a.assignment() == Assignment.RESTING) {
                morale += 1;
            }
            if (a.trait() == Trait.ANXIOUS && morale < 0) {
                morale -= 1;
            }
            a.perceive(hunger, fatigue, morale);
        }
    }
}
