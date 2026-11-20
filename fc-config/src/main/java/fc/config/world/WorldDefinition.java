package fc.config.world;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 世界定义（配置驱动）：世界的全部初始数据都来自这里，核心代码不硬编码任何地点/航线。
 *
 * <p>由 {@code world.xml}（XSD 校验）装载，再由 {@code fc-sim.WorldAssembler}
 * 装配成 {@code fc-world} 的领域对象。本记录只承载纯数据。</p>
 */
public record WorldDefinition(
        String name,
        long epochDay,
        List<LocationDef> locations,
        List<LinkDef> links,
        List<ShipDef> ships,
        List<AgentDef> agents,
        List<FactionDef> factions,
        EconomyDef economy,
        long seed) {

    /** 默认世界种子（确定性随机的起点）。 */
    public static final long DEFAULT_SEED = 20261120L;

    public WorldDefinition {
        locations = List.copyOf(locations);
        links = List.copyOf(links);
        ships = List.copyOf(ships);
        agents = List.copyOf(agents);
        factions = List.copyOf(factions);
    }

    /** 兼容：无主体/势力/经济、默认种子。 */
    public WorldDefinition(String name, long epochDay, List<LocationDef> locations,
                           List<LinkDef> links, List<ShipDef> ships) {
        this(name, epochDay, locations, links, ships, List.of(), List.of(),
                EconomyDef.EMPTY, DEFAULT_SEED);
    }

    /** 兼容：有主体、无势力/经济、默认种子。 */
    public WorldDefinition(String name, long epochDay, List<LocationDef> locations,
                           List<LinkDef> links, List<ShipDef> ships, List<AgentDef> agents) {
        this(name, epochDay, locations, links, ships, agents, List.of(),
                EconomyDef.EMPTY, DEFAULT_SEED);
    }

    /** 兼容：有主体与经济、无势力、默认种子。 */
    public WorldDefinition(String name, long epochDay, List<LocationDef> locations,
                           List<LinkDef> links, List<ShipDef> ships, List<AgentDef> agents,
                           EconomyDef economy) {
        this(name, epochDay, locations, links, ships, agents, List.of(), economy, DEFAULT_SEED);
    }

    /** 经济参数（资源价格、配方、玩家初始信用点、银行资本与日利率）。 */
    public record EconomyDef(List<ResourceDef> resources, List<RecipeDef> recipes,
                             BigDecimal playerCredits, BigDecimal bankCapital,
                             BigDecimal loanDailyRate) {

        public static final EconomyDef EMPTY = new EconomyDef(
                List.of(), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        public EconomyDef {
            resources = List.copyOf(resources);
            recipes = List.copyOf(recipes);
        }

        /** 兼容：无银行参数。 */
        public EconomyDef(List<ResourceDef> resources, List<RecipeDef> recipes,
                          BigDecimal playerCredits) {
            this(resources, recipes, playerCredits, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    /**
     * 地点定义。kind ∈ StarSystem / Planet / OrbitalStation / Anomaly / Colony / Facility。
     *
     * @param credits     该地点公共账户的信用点
     * @param colonyLevel 殖民地等级（非殖民地为 0；前哨站为 0 级殖民地）
     * @param owner       殖民地归属：{@code player} 或 {@code faction:<id>}；非殖民地为 null
     * @param population  殖民地人口（非殖民地为 0）
     * @param upgrades    升级要求
     */
    public record LocationDef(
            long id,
            String kind,
            String name,
            Long parentId,
            Map<String, BigDecimal> stock,
            BigDecimal credits,
            ProductionDef production,
            int colonyLevel,
            String owner,
            int population,
            List<UpgradeDef> upgrades) {

        public LocationDef {
            stock = Map.copyOf(stock);
            upgrades = List.copyOf(upgrades);
        }

        /** 兼容：无财政/殖民地扩展。 */
        public LocationDef(long id, String kind, String name, Long parentId,
                           Map<String, BigDecimal> stock, ProductionDef production) {
            this(id, kind, name, parentId, stock, BigDecimal.ZERO, production,
                    0, null, 0, List.of());
        }

        public boolean isColony() {
            return "Colony".equals(kind);
        }
    }

    /** 殖民地升级要求：货物 + 信用点。 */
    public record UpgradeDef(int toLevel, Map<String, BigDecimal> goods, BigDecimal credits) {
        public UpgradeDef {
            goods = Map.copyOf(goods);
        }
    }

    /** 每日产出（M1 库存阶段使用）。 */
    public record ProductionDef(String resource, BigDecimal perDay) {
    }

    /** 航线定义。kind ∈ WarpRoute / SurfaceRoute / InternalLane；M1 起按无向边处理。 */
    public record LinkDef(long from, long to, String kind, int ticks, double risk, BigDecimal cost) {
    }

    /** 舰船定义：初始位置与货舱（含 fuel）。 */
    public record ShipDef(long id, String name, long at, Map<String, BigDecimal> cargo) {
        public ShipDef {
            cargo = Map.copyOf(cargo);
        }
    }

    /**
     * 主体定义（船员/居民/使者统一类型）。
     * role ∈ CREW / RESIDENT / ENVOY；trait ∈ DILIGENT / LAZY / STOIC / ANXIOUS。
     */
    public record AgentDef(long id, String name, String role, long at, String trait, int skill) {
    }

    /**
     * 势力定义。
     *
     * @param kind      TRADE / COLONY / MILITARY / SCIENCE
     * @param seat      势力中心地点
     * @param priority  最关心的货物（资源稳定标识）
     * @param relations 对他方势力的初始关系
     */
    public record FactionDef(long id, String name, String kind, long seat, String doctrine,
                             String priority, BigDecimal reserve, BigDecimal stockTarget,
                             BigDecimal tradeUnitPrice, BigDecimal taxRate,
                             List<RelationDef> relations) {
        public FactionDef {
            relations = List.copyOf(relations);
        }
    }

    /** 势力间初始关系（-100..100）。 */
    public record RelationDef(long other, int value) {
    }
}
