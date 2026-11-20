package fc.world;

import fc.world.Ids.AgentId;
import fc.world.Ids.LocationId;
import fc.world.time.Stardate;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 世界状态根（WorldState）。
 *
 * <p>纯数据根：地点图 + 行为主体 + 权威账本 + 编年史接口 + 当前时间。
 * 只在 {@code fc-world}（最底层），{@code GameLoop}/{@code TickPipeline} 在 {@code fc-sim}，
 * 从而消除 World ↔ TickPipeline ↔ Chronicle 的循环依赖。</p>
 */
public final class WorldState {

    private final LocationGraph graph;
    private final Map<AgentId, Agent> agents;
    private final ResourceLedger ledger;
    private final Chronicle chronicle;
    private final Map<LocationId, LocationId> parents;
    private final Map<String, WorkUnit> workUnits = new HashMap<>();
    private java.math.BigDecimal credits = java.math.BigDecimal.ZERO;
    private Stardate now;

    public WorldState(LocationGraph graph, Map<AgentId, Agent> agents,
                      ResourceLedger ledger, Chronicle chronicle, Stardate now) {
        this(graph, agents, ledger, chronicle, now, Map.of());
    }

    public WorldState(LocationGraph graph, Map<AgentId, Agent> agents,
                      ResourceLedger ledger, Chronicle chronicle, Stardate now,
                      Map<LocationId, LocationId> parents) {
        this.graph = graph;
        this.agents = new HashMap<>(agents);
        this.ledger = ledger;
        this.chronicle = chronicle;
        this.parents = new HashMap<>(parents);
        this.now = now;
    }

    /** 玩家（组织）信用点余额。 */
    public java.math.BigDecimal credits() {
        return credits;
    }

    public void setCredits(java.math.BigDecimal next) {
        this.credits = next;
    }

    /** 收支信用点（负数为支出）。 */
    public void addCredits(java.math.BigDecimal delta) {
        this.credits = this.credits.add(delta);
    }

    /** 地点的父地点（无父返回 null）。层级来自配置，不属于 sealed 模型。 */
    public LocationId parentOf(LocationId id) {
        return parents.get(id);
    }

    /** 某地点的直接子地点。 */
    public java.util.List<LocationId> childrenOf(LocationId parent) {
        java.util.List<LocationId> out = new java.util.ArrayList<>();
        for (Map.Entry<LocationId, LocationId> e : parents.entrySet()) {
            if (e.getValue().equals(parent)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    public LocationGraph graph() {
        return graph;
    }

    public Map<AgentId, Agent> agents() {
        return Collections.unmodifiableMap(agents);
    }

    /** 可变的主体集合（tick 阶段用；主体在装配时一次性建立）。 */
    public Map<AgentId, Agent> agentsMutable() {
        return agents;
    }

    /** 进行中的工作单元（M3 起由生产配方生成；M2 由测试/配置注入）。 */
    public Map<String, WorkUnit> workUnits() {
        return Collections.unmodifiableMap(workUnits);
    }

    /** 更新某工作单元的进度。 */
    public void putWorkUnit(String key, WorkUnit unit) {
        if (unit.done()) {
            workUnits.remove(key);
        } else {
            workUnits.put(key, unit);
        }
    }

    public ResourceLedger ledger() {
        return ledger;
    }

    public Chronicle chronicle() {
        return chronicle;
    }

    public Stardate now() {
        return now;
    }

    /** 推进世界时间（仅 tick 管线调用）。 */
    public void advanceTo(Stardate next) {
        this.now = next;
    }
}
