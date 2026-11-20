package fc.sim.tick;

import fc.world.Agent;
import fc.world.WorldState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 主体遍历工具：一律按主体 id 升序，保证同种子下阶段内处理顺序一致。
 */
final class Agents {

    private Agents() {
    }

    static List<Agent> sorted(WorldState state) {
        List<Agent> list = new ArrayList<>(state.agents().values());
        list.sort(Comparator.comparingLong(a -> a.id().id()));
        return list;
    }
}
