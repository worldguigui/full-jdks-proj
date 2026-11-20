package fc.colony;

import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Link;
import fc.world.Location;
import fc.world.ResourceKind;
import fc.world.SupplyLine;
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
 * 补给线服务：建立/取消固定间隔的货物调运。
 *
 * <p>补给线本身不发货；每个到期日由 tick 阶段 7 从来源地点的权威账本
 * 划出货物、生成一次在途 {@code Transfer}。运输天数按两地最短航线计算。</p>
 */
public final class LogisticsService {

    private LogisticsService() {
    }

    /** 操作结果。 */
    public record Result(boolean ok, String message) {
    }

    /** 建立补给线。intervalDays 为发车间隔（1..30 天）。 */
    public static Result establish(WorldState state, String id, Ids.LocationId from,
                                   Ids.LocationId to, ResourceKind kind,
                                   BigDecimal amountPerRun, int intervalDays) {
        if (id == null || id.isBlank()) {
            return new Result(false, "补给线 id 不能为空。");
        }
        Ids.SupplyLineId lineId = new Ids.SupplyLineId(id);
        if (state.supplyLines().containsKey(lineId)) {
            return new Result(false, "补给线已存在: " + id);
        }
        if (state.graph().get(from) == null || state.graph().get(to) == null) {
            return new Result(false, "补给线端点不存在。");
        }
        if (from.equals(to)) {
            return new Result(false, "补给线两端不能相同。");
        }
        if (amountPerRun == null || amountPerRun.signum() <= 0) {
            return new Result(false, "每批数量必须为正。");
        }
        if (intervalDays < 1 || intervalDays > 30) {
            return new Result(false, "发车间隔必须在 1..30 天。");
        }
        Optional<Integer> ticks = routeTicks(state, from, to);
        if (ticks.isEmpty()) {
            return new Result(false, "两地之间没有可用航线。");
        }
        SupplyLine line = new SupplyLine(lineId, from, to, kind, amountPerRun,
                intervalDays, ticks.get());
        state.addSupplyLine(line);
        state.chronicle().record(new GameEvent.SupplyLineEstablished(
                state.now(), to, lineId, from, to, kind, amountPerRun));
        return new Result(true, "补给线 " + id + " 已建立：" + nameOf(state, from) + " → "
                + nameOf(state, to) + "，每 " + intervalDays + " 天 "
                + amountPerRun.stripTrailingZeros().toPlainString() + " "
                + kind.displayName() + "（在途 " + ticks.get() + " 天）。");
    }

    /** 取消补给线（已在途的货物继续运输）。 */
    public static Result cancel(WorldState state, String id) {
        Ids.SupplyLineId lineId = new Ids.SupplyLineId(id);
        if (!state.supplyLines().containsKey(lineId)) {
            return new Result(false, "补给线不存在: " + id);
        }
        state.removeSupplyLine(lineId);
        return new Result(true, "补给线 " + id + " 已取消。");
    }

    /** 补给线列表（CLI 展示）。 */
    public static String list(WorldState state) {
        if (state.supplyLines().isEmpty()) {
            return "尚未建立补给线。用 supply add 建立。";
        }
        StringBuilder sb = new StringBuilder("---- 补给线 ----\n");
        for (SupplyLine line : state.supplyLines().values()) {
            sb.append("  ").append(line.id())
                    .append(line.active() ? " 启用" : " 停用")
                    .append("｜").append(nameOf(state, line.from()))
                    .append(" → ").append(nameOf(state, line.to()))
                    .append("｜每 ").append(line.intervalDays()).append(" 天 ")
                    .append(line.amountPerRun().stripTrailingZeros().toPlainString())
                    .append(' ').append(line.resource().displayName())
                    .append("｜在途 ").append(line.travelTicks()).append(" 天")
                    .append("｜已发车 ").append(line.deliveredRuns()).append(" 批\n");
        }
        return sb.toString();
    }

    /** 最短航线天数（无向边，按 tick 数）。 */
    private static Optional<Integer> routeTicks(WorldState state, Ids.LocationId from,
                                                Ids.LocationId to) {
        Map<Ids.LocationId, List<Link>> adj = new HashMap<>();
        for (Link link : state.graph().links()) {
            adj.computeIfAbsent(link.from(), k -> new ArrayList<>()).add(link);
            adj.computeIfAbsent(link.to(), k -> new ArrayList<>()).add(link);
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
            if (cur.id().equals(to)) {
                return Optional.of(cur.dist());
            }
            for (Link link : adj.getOrDefault(cur.id(), List.of())) {
                Ids.LocationId next = link.from().equals(cur.id()) ? link.to() : link.from();
                int nd = cur.dist() + link.travelTicks();
                if (nd < dist.getOrDefault(next, Integer.MAX_VALUE)) {
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

    private static String nameOf(WorldState state, Ids.LocationId id) {
        Location loc = state.graph().get(id);
        return loc == null ? ("#" + id.id()) : loc.name();
    }
}
