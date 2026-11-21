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

        /** 航线天数修饰：按倍率缩短，最低 1 天；燃料成本不变。 */
        public Route withTickFactor(BigDecimal factor) {
            if (factor == null || factor.signum() <= 0 || totalTicks <= 0) {
                return this;
            }
            int scaled = Math.max(1, BigDecimal.valueOf(totalTicks).multiply(factor)
                    .setScale(0, java.math.RoundingMode.HALF_UP).intValue());
            return new Route(links, scaled, totalCost);
        }
    }

    /** 相邻边。 */
    private record Edge(LocationId to, Link link) {
    }

    /** 优先队列中的不可变条目：距离与成本在入队时固定。 */
    private record Node(LocationId id, int ticks, BigDecimal cost) {
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
        Map<LocationId, BigDecimal> costSoFar = new HashMap<>();
        Map<LocationId, Edge> prev = new LinkedHashMap<>();
        // 先比 tick 数，并列再比累计成本（与类文档一致）。
        // 队列条目不可变；发现更优路径时插入新条目，旧条目出队时被跳过。
        PriorityQueue<Node> pq = new PriorityQueue<>(Comparator
                .comparingInt(Node::ticks)
                .thenComparing(Node::cost)
                .thenComparingLong(n -> n.id().id()));
        dist.put(from, 0);
        costSoFar.put(from, BigDecimal.ZERO);
        pq.add(new Node(from, 0, BigDecimal.ZERO));

        while (!pq.isEmpty()) {
            Node cur = pq.poll();
            if (cur.ticks() != dist.getOrDefault(cur.id(), Integer.MAX_VALUE)
                    || cur.cost().compareTo(costSoFar.getOrDefault(cur.id(),
                            BigDecimal.ZERO)) != 0) {
                continue;
            }
            if (cur.id().equals(to)) {
                break;
            }
            for (Edge e : adj.getOrDefault(cur.id(), List.of())) {
                int nd = cur.ticks() + e.link().travelTicks();
                BigDecimal nc = cur.cost().add(e.link().cost());
                Integer oldDist = dist.get(e.to());
                BigDecimal oldCost = costSoFar.getOrDefault(e.to(), null);
                boolean better = oldDist == null
                        || nd < oldDist
                        || (nd == oldDist && nc.compareTo(oldCost) < 0);
                if (better) {
                    dist.put(e.to(), nd);
                    costSoFar.put(e.to(), nc);
                    prev.put(e.to(), e);
                    pq.add(new Node(e.to(), nd, nc));
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
