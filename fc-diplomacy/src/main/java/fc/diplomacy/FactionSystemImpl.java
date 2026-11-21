package fc.diplomacy;

import fc.sim.spi.FactionSystem;
import fc.sim.tick.TickContext;
import fc.world.Faction;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Link;
import fc.world.Location;
import fc.world.MoneyLedger;
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
 * 势力 AI（阶段 3）。
 *
 * <p>每个势力围绕配置的优先货物与库存目标行动：当中心地点库存不足时，
 * 在自己财力允许的范围内从最近的富余地点采购，并签订一次在途运输。
 * 货物从来源地点的账本分区转入在途分区、再抵达中心，全程守恒；
 * 采购款从势力账户付给来源地点账户，资金总量同样守恒。</p>
 *
 * <p>采购节奏由「日期 + 势力 id」取模错开，保证同日只有一个势力下单，
 * 且同种子下顺序与结果完全一致。</p>
 */
public final class FactionSystemImpl implements FactionSystem {

    /** 采购冷却（天）。 */
    private static final int PROCUREMENT_INTERVAL = 4;
    /** 单次采购下限。 */
    private static final BigDecimal MIN_LOT = BigDecimal.ONE;

    /** 关系漂移周期（天）。 */
    private static final int RELATION_PERIOD = 7;

    @Override
    public void onTick(WorldState state, TickContext ctx) {
        for (Faction f : state.factions().values()) {
            if (Math.floorMod(state.now().day() + f.id().id(), PROCUREMENT_INTERVAL) != 0) {
                continue;
            }
            procure(state, ctx, f);
            offerCommission(state, ctx, f);
        }
        CommissionService.failExpired(state, ctx);
        driftRelations(state, ctx);
    }

    /** 优先货物短缺且没有待接委托时，发布采购委托（交付到势力中心）。 */
    private void offerCommission(WorldState state, TickContext ctx, Faction f) {
        BigDecimal current = state.goods(f.seat(), f.priorityGood());
        if (current.compareTo(f.stockTarget()) >= 0) {
            return;
        }
        for (fc.world.Commission c : state.commissions().values()) {
            if (c.faction().equals(f.id()) && c.status() == fc.world.Commission.Status.OFFERED) {
                return;
            }
        }
        BigDecimal amount = f.stockTarget().subtract(current).min(new BigDecimal("20"))
                .max(new BigDecimal("2"));
        String id = state.nextCommissionId();
        BigDecimal reward = amount.multiply(f.tradeUnitPrice())
                .multiply(new BigDecimal("2")).add(new BigDecimal("50"))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        fc.world.Commission commission = new fc.world.Commission(
                id, f.id(), f.priorityGood(), amount, f.seat(),
                state.now().plusDays(10), reward);
        state.addCommission(commission);
        ctx.events().enqueue(new GameEvent.CommissionOffered(
                state.now(), f.seat(), id, f.id(), f.priorityGood(), amount,
                f.seat(), commission.deadline(), reward));
        ctx.report(f.name() + " 发布委托 " + id + "："
                + amount.stripTrailingZeros().toPlainString() + " "
                + f.priorityGood().id());
    }

    /** 势力间关系每 7 天确定性漂移 ±1（FACTIONS 流）。 */
    private void driftRelations(WorldState state, TickContext ctx) {
        if (Math.floorMod(state.now().day(), RELATION_PERIOD) != 0) {
            return;
        }
        var rng = ctx.rng().stream(fc.sim.rng.RngStreams.FACTIONS);
        for (String key : new java.util.TreeSet<>(state.relations().snapshot().keySet())) {
            String[] pair = key.split(":");
            Ids.FactionId a = new Ids.FactionId(Long.parseLong(pair[0]));
            Ids.FactionId b = new Ids.FactionId(Long.parseLong(pair[1]));
            if (state.faction(a) == null || state.faction(b) == null) {
                continue;
            }
            int delta = rng.nextBoolean() ? 1 : -1;
            state.relations().adjust(a, b, delta);
        }
    }

    private void procure(WorldState state, TickContext ctx, Faction f) {
        Ids.LocationId seat = f.seat();
        if (state.graph().get(seat) == null) {
            return;
        }
        BigDecimal current = state.goods(seat, f.priorityGood());
        BigDecimal shortfall = f.stockTarget().subtract(current);
        if (shortfall.compareTo(MIN_LOT) < 0) {
            return;
        }
        Optional<Route> route = nearestSurplus(state, seat, f.priorityGood(), shortfall);
        if (route.isEmpty()) {
            return;
        }
        Route r = route.get();
        BigDecimal amount = shortfall.min(r.available().divide(BigDecimal.TWO,
                java.math.RoundingMode.DOWN)).max(MIN_LOT);
        if (amount.compareTo(r.available()) > 0) {
            amount = r.available();
        }
        BigDecimal cost = amount.multiply(f.tradeUnitPrice());
        BigDecimal balance = state.money().balance(f.account());
        if (balance.compareTo(cost) < 0) {
            return;
        }

        state.money().transfer(state.now(), f.account(),
                MoneyLedger.locationAccount(r.from()), cost, "势力采购 " + f.priorityGood().id());
        var transfer = state.startTransfer(r.from(), seat, f.priorityGood(), amount,
                r.ticks(), "势力采购 " + f.name());
        ctx.events().enqueue(new GameEvent.TransferStarted(
                state.now(), seat, transfer.id(), r.from(), seat, f.priorityGood(),
                amount, transfer.totalTicks()));
        ctx.events().enqueue(new GameEvent.FactionAction(
                state.now(), seat, f.id(), "保障" + f.priorityGood().displayName(),
                "向 " + nameOf(state, r.from()) + " 采购 "
                        + amount.stripTrailingZeros().toPlainString() + " "
                        + f.priorityGood().id() + "，花费 "
                        + cost.stripTrailingZeros().toPlainString() + " 信用点"));
        ctx.report(f.name() + " 采购 " + f.priorityGood().displayName() + " "
                + amount.stripTrailingZeros().toPlainString());
    }

    private record Route(Ids.LocationId from, int ticks, BigDecimal available) {
    }

    /**
     * 从中心出发，按航程（tick 数）找最近的、优先货物富余的地点。
     * 距离用 Dijkstra 计算，邻接表按 id 排序，保证确定性。
     */
    private static Optional<Route> nearestSurplus(WorldState state, Ids.LocationId from,
                                                  ResourceKind kind, BigDecimal need) {
        Map<Ids.LocationId, List<Edge>> adj = new HashMap<>();
        for (Link link : state.graph().links()) {
            adj.computeIfAbsent(link.from(), k -> new ArrayList<>()).add(new Edge(link.to(), link));
            adj.computeIfAbsent(link.to(), k -> new ArrayList<>()).add(new Edge(link.from(), link));
        }
        adj.values().forEach(list -> list.sort(Comparator.comparingLong(e -> e.to().id())));

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
            if (!cur.id().equals(from)) {
                BigDecimal stock = state.goods(cur.id(), kind);
                if (stock.compareTo(MIN_LOT) >= 0) {
                    return Optional.of(new Route(cur.id(), cur.dist(), stock));
                }
            }
            for (Edge e : adj.getOrDefault(cur.id(), List.of())) {
                int nd = cur.dist() + e.link().travelTicks();
                if (nd < dist.getOrDefault(e.to(), Integer.MAX_VALUE)) {
                    dist.put(e.to(), nd);
                    queue.add(new Node(e.to(), nd));
                }
            }
        }
        return Optional.empty();
    }

    private record Edge(Ids.LocationId to, Link link) {
    }

    /** 优先队列中的不可变条目。 */
    private record Node(Ids.LocationId id, int dist) {
    }

    private static String nameOf(WorldState state, Ids.LocationId id) {
        Location loc = state.graph().get(id);
        return loc == null ? ("#" + id.id()) : loc.name();
    }
}
