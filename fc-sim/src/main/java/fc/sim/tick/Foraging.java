package fc.sim.tick;

import fc.world.Ids;
import fc.world.Link;
import fc.world.Location;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 觅食：在附近地点（按无向航线，限定跳数）寻找有口粮的地方。
 *
 * <p>这是「决策 → 行动」里的"移动"分支：饿了且本地无粮时，主体会自行迁徙到最近有粮处。
 * BFS 邻居按地点 id 排序，保证同种子下选择一致。</p>
 */
final class Foraging {

    private Foraging() {
    }

    static boolean hasFood(Location loc) {
        return loc != null && loc.stock().amount("food").compareTo(BigDecimal.ONE) >= 0;
    }

    /** 找最近有粮地点（不含自身），限定最大跳数。 */
    static Optional<Ids.LocationId> nearestFood(WorldState state, Ids.LocationId from, int maxHops) {
        Map<Ids.LocationId, List<Ids.LocationId>> adj = new HashMap<>();
        for (Link l : state.graph().links()) {
            adj.computeIfAbsent(l.from(), k -> new ArrayList<>()).add(l.to());
            adj.computeIfAbsent(l.to(), k -> new ArrayList<>()).add(l.from());
        }
        adj.values().forEach(list -> list.sort(Comparator.comparingLong(Ids.LocationId::id)));

        Map<Ids.LocationId, Integer> depth = new HashMap<>();
        Deque<Ids.LocationId> queue = new ArrayDeque<>();
        depth.put(from, 0);
        queue.add(from);
        while (!queue.isEmpty()) {
            Ids.LocationId cur = queue.poll();
            int d = depth.get(cur);
            if (d >= maxHops) {
                continue;
            }
            for (Ids.LocationId next : adj.getOrDefault(cur, List.of())) {
                if (depth.containsKey(next)) {
                    continue;
                }
                depth.put(next, d + 1);
                Location loc = state.graph().get(next);
                if (hasFood(loc)) {
                    return Optional.of(next);
                }
                queue.add(next);
            }
        }
        return Optional.empty();
    }
}
