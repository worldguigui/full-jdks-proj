package fc.sim.tick;

import fc.world.Ids;
import fc.world.Link;
import fc.world.ResourceKind;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * 觅食：沿无向航线寻找最近的、有口粮的地点，并给出所需行程天数。
 *
 * <p>迁徙按航线耗时推进，不再瞬移：主体进入 {@code TRAVELING} 状态，
 * 由感知阶段逐日推进行程，抵达后才更新所在地点。距离按 tick 数加权，
 * 并列时取 id 小者，保证同种子下选择一致。</p>
 */
final class Foraging {

    private Foraging() {
    }

    /** 觅食目的地与所需天数。 */
    record Destination(Ids.LocationId id, int ticks) {
    }

    static boolean hasFood(WorldState state, Ids.LocationId at) {
        return state.goods(at, ResourceKind.FOOD).compareTo(BigDecimal.ONE) >= 0;
    }

    /** 找最近有粮地点（不含自身），限定最大行程天数。 */
    static Optional<Destination> nearestFood(WorldState state, Ids.LocationId from, int maxTicks) {
        Map<Ids.LocationId, List<Link>> adj = new HashMap<>();
        for (Link l : state.graph().links()) {
            adj.computeIfAbsent(l.from(), k -> new ArrayList<>()).add(l);
            adj.computeIfAbsent(l.to(), k -> new ArrayList<>()).add(l);
        }
        adj.values().forEach(list -> list.sort(Comparator.comparingLong(l -> l.to().id())));

        Map<Ids.LocationId, Integer> dist = new HashMap<>();
        PriorityQueue<Node> queue = new PriorityQueue<>(Comparator
                .comparingInt(Node::dist)
                .thenComparingLong(n -> n.id().id()));
        dist.put(from, 0);
        queue.add(new Node(from, 0));
        while (!queue.isEmpty()) {
            Node cur = queue.poll();
            if (cur.dist() != dist.getOrDefault(cur.id(), Integer.MAX_VALUE)) {
                continue;
            }
            if (!cur.id().equals(from) && hasFood(state, cur.id())) {
                return Optional.of(new Destination(cur.id(), cur.dist()));
            }
            if (cur.dist() >= maxTicks) {
                continue;
            }
            for (Link link : adj.getOrDefault(cur.id(), List.of())) {
                Ids.LocationId next = link.from().equals(cur.id()) ? link.to() : link.from();
                int nd = cur.dist() + link.travelTicks();
                if (nd <= maxTicks && nd < dist.getOrDefault(next, Integer.MAX_VALUE)) {
                    dist.put(next, nd);
                    queue.add(new Node(next, nd));
                }
            }
        }
        return Optional.empty();
    }

    /** 优先队列中的不可变条目。 */
    private record Node(Ids.LocationId id, int dist) {
    }
}
