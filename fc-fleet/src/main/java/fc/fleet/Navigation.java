package fc.fleet;

import fc.world.Ids.LocationId;
import fc.world.Link;
import fc.world.LocationGraph;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * 导航：在 {@link LocationGraph} 上求两点间的最短航路（按 tick 数加权）。
 *
 * <p>M1 把航线视为**无向边**（配置里的 from/to 可双向通行）；M3 起按有向图与航线状态细化。</p>
 */
public final class Navigation {

    private Navigation() {
    }

    /** 一条航路：经过的边、总 tick、总燃料成本。 */
    public record Route(List<Link> links, int totalTicks, BigDecimal totalCost) {
        public Route {
            links = List.copyOf(links);
        }
    }

    /** 相邻边。 */
    private record Edge(LocationId to, Link link) {
    }

    /** 求最短航路（按 totalTicks 最小；并列时取 cost 小者）。 */
    public static Optional<Route> find(LocationGraph graph, LocationId from, LocationId to) {
        if (from.equals(to)) {
            return Optional.of(new Route(List.of(), 0, BigDecimal.ZERO));
        }
        Map<LocationId, List<Edge>> adj = new HashMap<>();
        for (Link l : graph.links()) {
            adj.computeIfAbsent(l.from(), k -> new ArrayList<>()).add(new Edge(l.to(), l));
            adj.computeIfAbsent(l.to(), k -> new ArrayList<>()).add(new Edge(l.from(), l));
        }

        Map<LocationId, Integer> dist = new HashMap<>();
        Map<LocationId, Edge> prev = new LinkedHashMap<>();
        PriorityQueue<LocationId> pq = new PriorityQueue<>(Comparator.comparingInt(
                id -> dist.getOrDefault(id, Integer.MAX_VALUE)));
        dist.put(from, 0);
        pq.add(from);

        while (!pq.isEmpty()) {
            LocationId cur = pq.poll();
            if (cur.equals(to)) {
                break;
            }
            int d = dist.get(cur);
            for (Edge e : adj.getOrDefault(cur, List.of())) {
                int nd = d + e.link().travelTicks();
                if (nd < dist.getOrDefault(e.to(), Integer.MAX_VALUE)) {
                    dist.put(e.to(), nd);
                    prev.put(e.to(), e);
                    pq.add(e.to());
                }
            }
        }

        if (!dist.containsKey(to)) {
            return Optional.empty();
        }
        // 回溯
        List<Link> path = new ArrayList<>();
        LocationId cur = to;
        while (!cur.equals(from)) {
            Edge e = prev.get(cur);
            if (e == null) {
                return Optional.empty();
            }
            path.add(0, e.link());
            cur = e.link().from().equals(cur) ? e.link().to() : e.link().from();
        }
        BigDecimal cost = BigDecimal.ZERO;
        for (Link l : path) {
            cost = cost.add(l.cost());
        }
        return Optional.of(new Route(path, dist.get(to), cost));
    }
}
