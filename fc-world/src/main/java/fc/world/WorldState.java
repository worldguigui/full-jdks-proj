package fc.world;

import fc.world.Ids.AgentId;
import fc.world.Ids.DecisionId;
import fc.world.Ids.FactionId;
import fc.world.Ids.LocationId;
import fc.world.Ids.SupplyLineId;
import fc.world.Ids.TransferId;
import fc.world.time.Stardate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 世界状态根（WorldState）。
 *
 * <p>纯数据根：地点图 + 主体 + 权威货物账本 + 财务账本 + 势力/殖民地/运输/决策 +
 * 编年史接口 + 当前时间。只在 {@code fc-world}（最底层）；{@code GameLoop} 与
 * {@code TickPipeline} 在 {@code fc-sim}，从而消除 World ↔ TickPipeline ↔ Chronicle
 * 的循环依赖。</p>
 *
 * <p>所有库存修改都经过本类，由 {@link ResourceLedger} 记账并同步地点视图；
 * 信用点只经过 {@link MoneyLedger}。</p>
 */
public final class WorldState implements WorldView {

    /** 风险修正：在指定日期前对某条航线生效。 */
    public record RiskModifier(BigDecimal delta, Stardate until) {
    }

    /** 玩家资产的食物/燃料日快照（D0 生存目标比较用）。 */
    public record CargoSnapshot(Stardate when, BigDecimal food, BigDecimal fuel) {
    }

    private final LocationGraph graph;
    private final Map<AgentId, Agent> agents;
    private final ResourceLedger ledger;
    private final MoneyLedger money;
    private final Chronicle chronicle;
    private final Map<LocationId, LocationId> parents;
    private final Map<String, WorkUnit> workUnits = new LinkedHashMap<>();

    private final Map<FactionId, Faction> factions = new TreeMap<>();
    private final Relations relations = new Relations();
    private final Map<LocationId, ColonyState> colonies = new TreeMap<>();
    private final Map<FactionId, Integer> reputation = new TreeMap<>();
    private final Map<TransferId, Transfer> transfers = new LinkedHashMap<>();
    private final Map<SupplyLineId, SupplyLine> supplyLines = new TreeMap<>();
    private final List<PendingDecision> decisions = new ArrayList<>();

    private final Map<String, RiskModifier> riskModifiers = new TreeMap<>();
    private final Map<LocationId, Stardate> productionDisruptions = new TreeMap<>();
    private final Map<ResourceKind, BigDecimal> priceShocks = new TreeMap<>();
    private final Map<ResourceKind, Stardate> priceShockUntil = new TreeMap<>();

    // ---- M4：知识、科研、外交、委托、工程与结局 ----
    private final Map<LocationId, FactionId> owners = new TreeMap<>();
    private final Map<LocationId, KnowledgeState> knowledge = new TreeMap<>();
    private final Map<LocationId, Integer> workerCapacity = new TreeMap<>();
    private final Map<LocationId, String> facilityKinds = new TreeMap<>();
    private final Map<LocationId, String> researchJobs = new TreeMap<>();
    private final Map<LocationId, SurveyTask> surveys = new TreeMap<>();
    private final java.util.Set<String> techs = new java.util.TreeSet<>();
    private final Map<FactionId, java.util.Set<Treaty>> treaties = new TreeMap<>();
    private final Map<String, Commission> commissions = new TreeMap<>();
    private final List<ShipOrder> shipOrders = new ArrayList<>();
    private final Map<String, Integer> projectProgress = new TreeMap<>();
    private final java.util.Set<String> completedProjects = new java.util.TreeSet<>();
    private final java.util.Set<String> startedProjects = new java.util.TreeSet<>();
    private final Map<String, LocationId> projectLocations = new TreeMap<>();
    private final Map<String, Integer> projectRequiredDays = new TreeMap<>();
    private final java.util.Set<String> goalsReached = new java.util.TreeSet<>();
    private final Map<Long, CargoSnapshot> cargoHistory = new TreeMap<>();
    private String ending;
    private int supplyDispatches;
    private long nextCommissionSeq = 1;
    private long nextOrderSeq = 1;
    private long nextShipSeq = 100;

    private long nextTransferId = 1;
    private long nextDecisionSeq = 1;
    private Stardate now;

    public WorldState(LocationGraph graph, Map<AgentId, Agent> agents,
                      ResourceLedger ledger, Chronicle chronicle, Stardate now) {
        this(graph, agents, ledger, new MoneyLedger(), chronicle, now, Map.of());
    }

    public WorldState(LocationGraph graph, Map<AgentId, Agent> agents,
                      ResourceLedger ledger, Chronicle chronicle, Stardate now,
                      Map<LocationId, LocationId> parents) {
        this(graph, agents, ledger, new MoneyLedger(), chronicle, now, parents);
    }

    public WorldState(LocationGraph graph, Map<AgentId, Agent> agents,
                      ResourceLedger ledger, MoneyLedger money, Chronicle chronicle,
                      Stardate now, Map<LocationId, LocationId> parents) {
        this.graph = graph;
        // 主体与父子层级按 id 排序保存，避免遍历顺序依赖哈希表
        this.agents = new TreeMap<>();
        this.agents.putAll(agents);
        this.ledger = ledger;
        this.money = money;
        this.chronicle = chronicle;
        this.parents = new TreeMap<>();
        this.parents.putAll(parents);
        this.now = now;
        this.cargoHistory.put(now.day(), new CargoSnapshot(now,
                playerStock(ResourceKind.FOOD), playerStock(ResourceKind.FUEL)));
    }

    // ---- 时间 ----

    @Override
    public Stardate now() {
        return now;
    }

    /** 推进世界时间（仅 tick 管线调用），并记录玩家资产的食物/燃料日快照。 */
    public void advanceTo(Stardate next) {
        this.now = next;
        recordCargoSnapshot();
    }

    /** 记录当前日的玩家资产食物/燃料快照（装配完成后也应调用一次）。 */
    public void recordCargoSnapshot() {
        cargoHistory.put(now.day(), new CargoSnapshot(now,
                playerStock(ResourceKind.FOOD), playerStock(ResourceKind.FUEL)));
        while (cargoHistory.size() > 64) {
            cargoHistory.remove(cargoHistory.keySet().iterator().next());
        }
    }

    // ---- 玩家资产与生存快照 ----

    /** 玩家控制的地点：玩家殖民地及其子设施。 */
    public java.util.Set<LocationId> playerLocationIds() {
        java.util.Set<LocationId> out = new java.util.TreeSet<>();
        for (ColonyState c : colonies.values()) {
            if (c.playerOwned()) {
                out.add(c.id());
                collectChildren(c.id(), out);
            }
        }
        return out;
    }

    private void collectChildren(LocationId parent, java.util.Set<LocationId> out) {
        for (Map.Entry<LocationId, LocationId> e : parents.entrySet()) {
            if (e.getValue().equals(parent) && out.add(e.getKey())) {
                collectChildren(e.getKey(), out);
            }
        }
    }

    /** 玩家资产中的某货物数量（玩家地点 + 舰船货舱 + 在途）。 */
    public BigDecimal playerStock(ResourceKind kind) {
        BigDecimal sum = BigDecimal.ZERO;
        for (LocationId id : playerLocationIds()) {
            sum = sum.add(goods(id, kind));
        }
        for (Location.Ship ship : graph.ships()) {
            sum = sum.add(goods(ship.id(), kind));
        }
        for (Transfer t : activeTransfers()) {
            if (t.resource() == kind) {
                sum = sum.add(t.amount());
            }
        }
        return sum;
    }

    /** 指定日期的玩家资产快照（无记录返回 null）。 */
    public CargoSnapshot cargoSnapshot(long day) {
        return cargoHistory.get(day);
    }

    public Map<Long, CargoSnapshot> cargoHistory() {
        return Collections.unmodifiableMap(new TreeMap<>(cargoHistory));
    }

    // ---- 地点与货物 ----

    public LocationGraph graph() {
        return graph;
    }

    public Location location(LocationId id) {
        return graph.get(id);
    }

    @Override
    public boolean hasLocation(LocationId id) {
        return graph.contains(id);
    }

    @Override
    public List<Link> linksFrom(LocationId id) {
        return graph.linksFrom(id);
    }

    /** 地点的权威库存（账本分区）。 */
    @Override
    public Stock stockAt(LocationId id) {
        return ledger.stock(id);
    }

    public BigDecimal goods(LocationId id, ResourceKind kind) {
        return ledger.stock(id).amount(kind);
    }

    /** 用完整快照覆盖库存；返回净变化。 */
    public Map<ResourceKind, BigDecimal> setStock(LocationId id, Stock next, String reason) {
        Map<ResourceKind, BigDecimal> delta = ledger.set(id, next, now, reason);
        syncStock(id);
        return delta;
    }

    /** 消耗货物；不足时抛异常（不存在负库存）。 */
    public void consume(LocationId id, ResourceKind kind, BigDecimal amount, String reason) {
        Stock next = stockAt(id).subtract(kind, amount);
        setStock(id, next, reason);
    }

    /** 尝试消耗货物；不足时返回 false 且不修改状态。 */
    public boolean tryConsume(LocationId id, ResourceKind kind, BigDecimal amount, String reason) {
        if (goods(id, kind).compareTo(amount) < 0) {
            return false;
        }
        consume(id, kind, amount, reason);
        return true;
    }

    /** 生产货物（总量增加）。 */
    public void produce(LocationId id, ResourceKind kind, BigDecimal amount, String reason) {
        setStock(id, stockAt(id).add(kind, amount), reason);
    }

    /** 同地点间转移（总量不变）。 */
    public void transferStock(LocationId from, LocationId to, ResourceKind kind,
                              BigDecimal amount, String reason) {
        ledger.transfer(from, to, kind, amount, now, reason);
        syncStock(from);
        syncStock(to);
    }

    /** 把库存同步回地点视图。 */
    public void syncStock(LocationId id) {
        Location loc = graph.get(id);
        if (loc != null) {
            graph.replace(loc.withStock(ledger.stock(id)));
        }
    }

    /** 校验所有地点视图与权威账本一致。 */
    public void verifyLedger() {
        Map<LocationId, Stock> snapshots = new LinkedHashMap<>();
        for (Map.Entry<LocationId, Location> e : graph.nodes().entrySet()) {
            snapshots.put(e.getKey(), e.getValue().stock());
        }
        ledger.verify(snapshots);
    }

    public ResourceLedger ledger() {
        return ledger;
    }

    // ---- 财务 ----

    public MoneyLedger money() {
        return money;
    }

    /** 玩家（组织）信用点余额。 */
    public BigDecimal credits() {
        return money.balance(MoneyLedger.PLAYER);
    }

    /** 修改玩家信用点（只用于初始注资/测试；常规收支必须两账户转账）。 */
    public void depositPlayer(BigDecimal amount, String reason) {
        money.deposit(now, MoneyLedger.PLAYER, amount, reason);
    }

    /** 从玩家账户支付给另一账户。 */
    public void payFromPlayer(String to, BigDecimal amount, String reason) {
        money.transfer(now, MoneyLedger.PLAYER, to, amount, reason);
    }

    // ---- 主体 ----

    public Map<AgentId, Agent> agents() {
        return Collections.unmodifiableMap(agents);
    }

    /** 可变的主体集合（tick 阶段用；主体在装配时一次性建立）。 */
    public Map<AgentId, Agent> agentsMutable() {
        return agents;
    }

    public Agent agent(AgentId id) {
        return agents.get(id);
    }

    /** 地点上的主体（按 id 排序）。 */
    public List<Agent> agentsAt(LocationId where) {
        List<Agent> out = new ArrayList<>();
        for (Agent a : agents.values()) {
            if (a.location().equals(where)) {
                out.add(a);
            }
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    /** 进行中的工作单元。 */
    public Map<String, WorkUnit> workUnits() {
        return Collections.unmodifiableMap(workUnits);
    }

    /** 移除工作单元（取消科研/维修时使用）。 */
    public WorkUnit removeWorkUnit(String key) {
        return workUnits.remove(key);
    }

    /** 更新某工作单元的进度。 */
    public void putWorkUnit(String key, WorkUnit unit) {
        if (unit.done()) {
            workUnits.remove(key);
        } else {
            workUnits.put(key, unit);
        }
    }

    // ---- 层级 ----

    public LocationId parentOf(LocationId id) {
        return parents.get(id);
    }

    public List<LocationId> childrenOf(LocationId parent) {
        List<LocationId> out = new ArrayList<>();
        for (Map.Entry<LocationId, LocationId> e : parents.entrySet()) {
            if (e.getValue().equals(parent)) {
                out.add(e.getKey());
            }
        }
        out.sort(LocationId::compareTo);
        return out;
    }

    /** 全部地点 id（按 id 升序，舰船也在图内）。 */
    public List<LocationId> locationIds() {
        return sortedLocationIds();
    }

    public Map<LocationId, LocationId> parents() {
        return Collections.unmodifiableMap(parents);
    }

    // ---- 势力与关系 ----

    public void addFaction(Faction faction) {
        factions.put(faction.id(), faction);
        reputation.putIfAbsent(faction.id(), 0);
    }

    public Map<FactionId, Faction> factions() {
        return Collections.unmodifiableMap(factions);
    }

    public Faction faction(FactionId id) {
        return factions.get(id);
    }

    public Relations relations() {
        return relations;
    }

    /** 玩家在某势力的声望（-100..100）。 */
    public int reputation(FactionId id) {
        return reputation.getOrDefault(id, 0);
    }

    public void adjustReputation(FactionId id, int delta) {
        int next = Math.max(-100, Math.min(100, reputation(id) + delta));
        reputation.put(id, next);
    }

    public Map<FactionId, Integer> reputations() {
        return Collections.unmodifiableMap(reputation);
    }

    // ---- 殖民地 ----

    public void addColony(ColonyState colony) {
        colonies.put(colony.id(), colony);
    }

    public ColonyState colony(LocationId id) {
        return colonies.get(id);
    }

    public Map<LocationId, ColonyState> colonies() {
        return Collections.unmodifiableMap(colonies);
    }

    /** 玩家的殖民地（按 id 排序）。 */
    public List<ColonyState> playerColonies() {
        List<ColonyState> out = new ArrayList<>();
        for (ColonyState c : colonies.values()) {
            if (c.playerOwned()) {
                out.add(c);
            }
        }
        return out;
    }

    // ---- 运输与补给线 ----

    /** 开始一次货物运输；货物立即进入在途分区。 */
    public Transfer startTransfer(LocationId from, LocationId to, ResourceKind kind,
                                  BigDecimal amount, int ticks, String reason) {
        if (ticks < 1) {
            throw new IllegalArgumentException("运输天数必须 ≥ 1: " + ticks);
        }
        if (graph.get(from) == null || graph.get(to) == null) {
            throw new IllegalArgumentException("运输端点不存在: " + from + " -> " + to);
        }
        TransferId id = new TransferId(nextTransferId++);
        ledger.holdForTransfer(id.id(), from, kind, amount, now, reason);
        syncStock(from);
        Transfer t = new Transfer(id, from, to, kind, amount, ticks, now, reason);
        transfers.put(id, t);
        return t;
    }

    /** 推进所有在途运输；抵达者结算并返回。 */
    public List<Transfer> advanceTransfers() {
        List<Transfer> arrived = new ArrayList<>();
        List<Transfer> snapshot = new ArrayList<>(transfers.values());
        snapshot.sort((a, b) -> a.id().compareTo(b.id()));
        for (Transfer t : snapshot) {
            if (t.state() != Transfer.State.IN_TRANSIT) {
                continue;
            }
            t.advance();
            if (t.arrived()) {
                ledger.deliverTransfer(t.id().id(), t.to(), t.resource(), t.amount(), now,
                        t.reason());
                syncStock(t.to());
                t.deliver();
                arrived.add(t);
            }
        }
        return arrived;
    }

    /** 取消运输，把在途货物退回来源。 */
    public void cancelTransfer(TransferId id, String reason) {
        Transfer t = transfers.get(id);
        if (t == null || t.state() != Transfer.State.IN_TRANSIT) {
            throw new IllegalArgumentException("运输不可取消: " + id);
        }
        ledger.cancelTransfer(id.id(), t.from(), t.resource(), t.amount(), now, reason);
        syncStock(t.from());
        t.cancel();
    }

    public Map<TransferId, Transfer> transfers() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(transfers));
    }

    public List<Transfer> activeTransfers() {
        List<Transfer> out = new ArrayList<>();
        for (Transfer t : transfers.values()) {
            if (t.state() == Transfer.State.IN_TRANSIT) {
                out.add(t);
            }
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    public Transfer transfer(TransferId id) {
        return transfers.get(id);
    }

    public void addSupplyLine(SupplyLine line) {
        if (supplyLines.containsKey(line.id())) {
            throw new IllegalArgumentException("补给线已存在: " + line.id());
        }
        supplyLines.put(line.id(), line);
    }

    public void removeSupplyLine(SupplyLineId id) {
        SupplyLine line = supplyLines.remove(id);
        if (line != null) {
            line.deactivate();
        }
    }

    public Map<SupplyLineId, SupplyLine> supplyLines() {
        return Collections.unmodifiableMap(supplyLines);
    }

    // ---- 决策 ----

    /** 新建待决决策；id 自动生成。 */
    public PendingDecision raiseDecision(String title, String description, LocationId where,
                                         FactionId faction, List<PendingDecision.Option> options) {
        DecisionId id = new DecisionId("D" + nextDecisionSeq++);
        PendingDecision d = new PendingDecision(id, title, description, where, faction, now, options);
        decisions.add(d);
        return d;
    }

    public List<PendingDecision> decisions() {
        return List.copyOf(decisions);
    }

    public List<PendingDecision> pendingDecisions() {
        List<PendingDecision> out = new ArrayList<>();
        for (PendingDecision d : decisions) {
            if (!d.resolved()) {
                out.add(d);
            }
        }
        return out;
    }

    public boolean hasPendingDecision() {
        for (PendingDecision d : decisions) {
            if (!d.resolved()) {
                return true;
            }
        }
        return false;
    }

    public PendingDecision decision(DecisionId id) {
        for (PendingDecision d : decisions) {
            if (d.id().equals(id)) {
                return d;
            }
        }
        return null;
    }

    // ---- 事件影响（风险/停产/价格冲击） ----

    private static String linkKey(LocationId a, LocationId b) {
        long low = Math.min(a.id(), b.id());
        long high = Math.max(a.id(), b.id());
        return low + ":" + high;
    }

    /** 航线额外风险（在 base risk 之上）。 */
    public BigDecimal riskModifier(LocationId a, LocationId b) {
        RiskModifier m = riskModifiers.get(linkKey(a, b));
        if (m == null || now.isAfter(m.until())) {
            return BigDecimal.ZERO;
        }
        return m.delta();
    }

    public void addRiskModifier(LocationId a, LocationId b, BigDecimal delta, int days) {
        Stardate until = now.plusDays(Math.max(1, days));
        riskModifiers.put(linkKey(a, b), new RiskModifier(delta, until));
    }

    public void clearExpiredModifiers() {
        riskModifiers.entrySet().removeIf(e -> now.isAfter(e.getValue().until()));
        productionDisruptions.entrySet().removeIf(e -> now.isAfter(e.getValue()));
        priceShockUntil.entrySet().removeIf(e -> now.isAfter(e.getValue()));
        priceShocks.keySet().retainAll(priceShockUntil.keySet());
    }

    public Map<String, RiskModifier> riskModifiers() {
        return Collections.unmodifiableMap(riskModifiers);
    }

    public void disruptProduction(LocationId id, int days) {
        productionDisruptions.put(id, now.plusDays(Math.max(1, days)));
    }

    public boolean productionDisrupted(LocationId id) {
        Stardate until = productionDisruptions.get(id);
        return until != null && !now.isAfter(until);
    }

    public Map<LocationId, Stardate> productionDisruptions() {
        return Collections.unmodifiableMap(productionDisruptions);
    }

    public void applyPriceShock(ResourceKind kind, BigDecimal multiplier, int days) {
        if (multiplier == null || multiplier.signum() <= 0) {
            throw new IllegalArgumentException("价格冲击乘数必须为正: " + multiplier);
        }
        BigDecimal clamped = multiplier.max(new BigDecimal("0.1"))
                .min(new BigDecimal("10"));
        priceShocks.put(kind, clamped);
        priceShockUntil.put(kind, now.plusDays(Math.max(1, days)));
    }

    /** 当前价格冲击乘数（无冲击为 1）。 */
    public BigDecimal priceShock(ResourceKind kind) {
        Stardate until = priceShockUntil.get(kind);
        if (until == null || now.isAfter(until)) {
            return BigDecimal.ONE;
        }
        return priceShocks.getOrDefault(kind, BigDecimal.ONE);
    }

    public Map<ResourceKind, BigDecimal> priceShocks() {
        Map<ResourceKind, BigDecimal> out = new TreeMap<>();
        for (ResourceKind kind : ResourceKind.GOODS) {
            out.put(kind, priceShock(kind));
        }
        return Collections.unmodifiableMap(out);
    }

    // ---- M4：地点归属与税率 ----

    /** 设置地点归属势力（装配期或征服时调用）。 */
    public void setLocationOwner(LocationId id, FactionId faction) {
        if (faction == null) {
            owners.remove(id);
        } else {
            owners.put(id, faction);
        }
    }

    public FactionId locationOwner(LocationId id) {
        return owners.get(id);
    }

    public Map<LocationId, FactionId> locationOwners() {
        return Collections.unmodifiableMap(owners);
    }

    /**
     * 某地点的交易税率：归属于势力且未签贸易协定时按势力税率；贸易协定减半。
     * 玩家自营地点与无主地点为 0。
     */
    public BigDecimal taxRateAt(LocationId id) {
        FactionId fid = owners.get(id);
        if (fid == null) {
            // 未记控制者的地点：若是某势力中心，同样视为该势力控制。
            for (Faction f : factions.values()) {
                if (f.seat().equals(id)) {
                    fid = f.id();
                    break;
                }
            }
        }
        if (fid == null) {
            return BigDecimal.ZERO;
        }
        Faction f = factions.get(fid);
        if (f == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal rate = f.taxRate();
        if (hasTreaty(fid, Treaty.TRADE)) {
            rate = rate.divide(BigDecimal.TWO, 6, java.math.RoundingMode.HALF_UP);
        }
        if (hasTech("TRADE_PROTOCOL")) {
            rate = rate.divide(BigDecimal.TWO, 6, java.math.RoundingMode.HALF_UP);
        }
        return rate;
    }

    // ---- M4：星图知识 ----

    public KnowledgeState knowledgeOf(LocationId id) {
        return knowledge.getOrDefault(id, KnowledgeState.UNKNOWN);
    }

    /** 设置知识状态（只允许提升，避免事件把已知地点改回未知）。 */
    public void setKnowledge(LocationId id, KnowledgeState next) {
        KnowledgeState cur = knowledgeOf(id);
        if (next != null && next.ordinal() > cur.ordinal()) {
            knowledge.put(id, next);
        } else if (!knowledge.containsKey(id)) {
            knowledge.put(id, cur);
        }
    }

    /** 已知/已勘察地点（至少达到给定档位，按 id 排序）。 */
    public List<LocationId> locationsWithKnowledge(KnowledgeState min) {
        List<LocationId> out = new ArrayList<>();
        for (LocationId id : sortedLocationIds()) {
            if (knowledgeOf(id).atLeast(min)) {
                out.add(id);
            }
        }
        return out;
    }

    public Map<LocationId, KnowledgeState> knowledge() {
        return Collections.unmodifiableMap(new TreeMap<>(knowledge));
    }

    /** 初始知识：把已勘察地点的相邻未知地点标记为已探测。 */
    public void seedDetectedNeighbours() {
        List<LocationId> known = locationsWithKnowledge(KnowledgeState.SURVEYED);
        for (LocationId id : known) {
            for (Link link : graph.linksFrom(id)) {
                LocationId other = link.from().equals(id) ? link.to() : link.from();
                if (knowledgeOf(other) == KnowledgeState.UNKNOWN) {
                    setKnowledge(other, KnowledgeState.DETECTED);
                }
            }
        }
    }

    // ---- M4：设施工位 ----

    /** 设置地点可容纳的工人数（0 或未设置表示不限制）。 */
    public void setWorkerCapacity(LocationId id, int capacity) {
        if (capacity > 0) {
            workerCapacity.put(id, capacity);
        } else {
            workerCapacity.remove(id);
        }
    }

    public int workerCapacity(LocationId id) {
        return workerCapacity.getOrDefault(id, 0);
    }

    public Map<LocationId, Integer> workerCapacities() {
        return Collections.unmodifiableMap(new TreeMap<>(workerCapacity));
    }

    /** 设施种类（LAB/WORKSHOP/SHIPYARD 等，来自配置）。 */
    public void setFacilityKind(LocationId id, String kind) {
        if (kind == null || kind.isBlank()) {
            facilityKinds.remove(id);
        } else {
            facilityKinds.put(id, kind);
        }
    }

    public String facilityKind(LocationId id) {
        return facilityKinds.getOrDefault(id, "");
    }

    public Map<LocationId, String> facilityKinds() {
        return Collections.unmodifiableMap(new TreeMap<>(facilityKinds));
    }

    // ---- M4：科研立项 ----

    /** 在实验室立项（数据成本由调用方先扣除）。 */
    public void startResearchJob(LocationId lab, String techId) {
        if (techId == null || techId.isBlank()) {
            throw new IllegalArgumentException("科技 id 不得为空");
        }
        researchJobs.put(lab, techId);
    }

    public String researchJobAt(LocationId lab) {
        return researchJobs.get(lab);
    }

    public String cancelResearchJob(LocationId lab) {
        return researchJobs.remove(lab);
    }

    public boolean isResearching(String techId) {
        return researchJobs.containsValue(techId);
    }

    public Map<LocationId, String> researchJobs() {
        return Collections.unmodifiableMap(new TreeMap<>(researchJobs));
    }

    // ---- M4：测绘任务 ----

    public void startSurvey(LocationId target, Ids.ShipId ship, int days) {
        if (surveys.containsKey(target)) {
            throw new IllegalArgumentException("该地点已在测绘: " + target);
        }
        surveys.put(target, new SurveyTask(target, ship, days));
    }

    public SurveyTask surveyAt(LocationId target) {
        return surveys.get(target);
    }

    public Map<LocationId, SurveyTask> surveys() {
        return Collections.unmodifiableMap(new TreeMap<>(surveys));
    }

    public SurveyTask cancelSurvey(LocationId target) {
        return surveys.remove(target);
    }

    public void removeSurvey(LocationId target) {
        surveys.remove(target);
    }

    // ---- M4：科技 ----

    public boolean hasTech(String techId) {
        return techs.contains(techId);
    }

    public void unlockTech(String techId) {
        techs.add(techId);
    }

    public java.util.Set<String> techs() {
        return Collections.unmodifiableSet(new java.util.TreeSet<>(techs));
    }

    // ---- M4：条约 ----

    public boolean hasTreaty(FactionId faction, Treaty treaty) {
        return treaties.getOrDefault(faction, java.util.Set.of()).contains(treaty);
    }

    public void addTreaty(FactionId faction, Treaty treaty) {
        treaties.computeIfAbsent(faction, k -> new java.util.TreeSet<>()).add(treaty);
    }

    public Map<FactionId, java.util.Set<Treaty>> treaties() {
        Map<FactionId, java.util.Set<Treaty>> out = new TreeMap<>();
        treaties.forEach((k, v) -> out.put(k, java.util.Set.copyOf(v)));
        return Collections.unmodifiableMap(out);
    }

    // ---- M4：委托 ----

    public String nextCommissionId() {
        return "C" + (nextCommissionSeq++);
    }

    public void addCommission(Commission commission) {
        commissions.put(commission.id(), commission);
    }

    public Commission commission(String id) {
        return commissions.get(id);
    }

    public Map<String, Commission> commissions() {
        return Collections.unmodifiableMap(new TreeMap<>(commissions));
    }

    // ---- M4：船坞订单 ----

    public String nextOrderId() {
        return "O" + (nextOrderSeq++);
    }

    public void addShipOrder(ShipOrder order) {
        shipOrders.add(order);
    }

    public List<ShipOrder> shipOrders() {
        return List.copyOf(shipOrders);
    }

    public Ids.ShipId nextShipId() {
        return new Ids.ShipId(nextShipSeq++);
    }

    /** 将新舰船接入地点图与账本（船坞交付）。 */
    public void addShip(Location.Ship ship) {
        graph.add(ship);
        ledger.open(ship.id(), ship.stock());
        money.open(MoneyLedger.locationAccount(ship.id()), BigDecimal.ZERO);
    }

    // ---- M4：终局工程与结局 ----

    public int projectProgress(String project) {
        return projectProgress.getOrDefault(project, 0);
    }

    /** 推进工程，返回是否完成。 */
    public boolean advanceProject(String project, int days, int requiredDays) {
        int next = Math.min(requiredDays, projectProgress(project) + days);
        projectProgress.put(project, next);
        if (next >= requiredDays) {
            completedProjects.add(project);
            return true;
        }
        return false;
    }

    public boolean projectCompleted(String project) {
        return completedProjects.contains(project);
    }

    public java.util.Set<String> completedProjects() {
        return Collections.unmodifiableSet(new java.util.TreeSet<>(completedProjects));
    }

    /** 启动终局工程（同一时间只允许一项；启动后锁定另一项）。 */
    public boolean startProject(String project, LocationId at, int requiredDays) {
        if (ending != null || startedProjects.contains(project)
                || !startedProjects.isEmpty()) {
            return false;
        }
        startedProjects.add(project);
        projectLocations.put(project, at);
        projectRequiredDays.put(project, Math.max(1, requiredDays));
        projectProgress.putIfAbsent(project, 0);
        return true;
    }

    public boolean projectStarted(String project) {
        return startedProjects.contains(project);
    }

    public LocationId projectLocation(String project) {
        return projectLocations.get(project);
    }

    public int projectRequiredDays(String project) {
        return projectRequiredDays.getOrDefault(project, 0);
    }

    public java.util.Set<String> startedProjects() {
        return Collections.unmodifiableSet(new java.util.TreeSet<>(startedProjects));
    }

    /** 记录阶段目标达成；返回是否为首次达成。 */
    public boolean markGoalReached(String goal) {
        return goalsReached.add(goal);
    }

    public java.util.Set<String> goalsReached() {
        return Collections.unmodifiableSet(new java.util.TreeSet<>(goalsReached));
    }

    public String ending() {
        return ending;
    }

    public void setEnding(String ending) {
        this.ending = ending;
    }

    /** 记录一次补给线发车（D0 目标）。 */
    public void recordSupplyDispatch() {
        supplyDispatches++;
    }

    public int supplyDispatches() {
        return supplyDispatches;
    }

    // ---- 编年史 ----

    public Chronicle chronicle() {
        return chronicle;
    }

    /** 供状态哈希使用的确定性排序视图。 */
    public List<LocationId> sortedLocationIds() {
        List<LocationId> ids = new ArrayList<>(graph.nodes().keySet());
        Collections.sort(ids);
        return ids;
    }
}
