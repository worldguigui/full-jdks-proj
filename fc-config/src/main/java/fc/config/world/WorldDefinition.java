package fc.config.world;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 世界定义（配置驱动）：世界的全部初始数据都来自这里，核心代码不硬编码任何地点/航线。
 *
 * <p>M1 起由 {@code world.xml}（XSD 校验）装载，再由 {@code fc-sim.WorldAssembler}
 * 装配成 {@code fc-world} 的领域对象。本记录只承载纯数据，不依赖 fc-world。</p>
 */
public record WorldDefinition(
        String name,
        long epochDay,
        List<LocationDef> locations,
        List<LinkDef> links,
        List<ShipDef> ships,
        List<AgentDef> agents,
        long seed) {

    /** 默认世界种子（确定性随机的起点）。 */
    public static final long DEFAULT_SEED = 20261120L;

    public WorldDefinition {
        locations = List.copyOf(locations);
        links = List.copyOf(links);
        ships = List.copyOf(ships);
        agents = List.copyOf(agents);
    }

    /** 兼容：无主体、默认种子。 */
    public WorldDefinition(String name, long epochDay, List<LocationDef> locations,
                           List<LinkDef> links, List<ShipDef> ships) {
        this(name, epochDay, locations, links, ships, List.of(), DEFAULT_SEED);
    }

    /** 兼容：有主体、默认种子。 */
    public WorldDefinition(String name, long epochDay, List<LocationDef> locations,
                           List<LinkDef> links, List<ShipDef> ships, List<AgentDef> agents) {
        this(name, epochDay, locations, links, ships, agents, DEFAULT_SEED);
    }

    /** 地点定义。kind ∈ StarSystem / Planet / OrbitalStation / Anomaly / Colony / Facility。 */
    public record LocationDef(
            long id,
            String kind,
            String name,
            Long parentId,
            Map<String, BigDecimal> stock,
            ProductionDef production) {

        public LocationDef {
            stock = Map.copyOf(stock);
        }
    }

    /** 每日产出（M1 库存阶段使用）。 */
    public record ProductionDef(String resource, BigDecimal perDay) {
    }

    /** 航线定义。kind ∈ WarpRoute / SurfaceRoute / InternalLane；M1 按无向边处理。 */
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
}
