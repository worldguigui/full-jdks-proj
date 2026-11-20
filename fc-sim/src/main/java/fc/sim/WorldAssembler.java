package fc.sim;

import fc.config.world.WorldDefinition;
import fc.world.Ids;
import fc.world.Link;
import fc.world.Location;
import fc.world.LocationGraph;
import fc.world.ResourceLedger;
import fc.world.Stock;
import fc.world.time.Stardate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界装配器：把配置（{@link WorldDefinition}）装配成 fc-world 领域对象。
 *
 * <p>这是「世界数据全部来自配置」的落地点——核心代码不含任何地点/航线硬编码。</p>
 */
public final class WorldAssembler {

    private WorldAssembler() {
    }

    /** 装配结果：世界图 + 账本 + 每日产出表 + 父子层级 + 主体 + 启程时间 + 随机种子。 */
    public record AssembledWorld(
            String name,
            LocationGraph graph,
            ResourceLedger ledger,
            Map<Ids.LocationId, Production> production,
            Map<Ids.LocationId, Ids.LocationId> parents,
            java.util.Map<Ids.AgentId, fc.world.Agent> agents,
            Stardate epoch,
            long seed) {
    }

    /** 每日产出。 */
    public record Production(String resource, BigDecimal perDay) {
    }

    /** 从世界定义装配。 */
    public static AssembledWorld assemble(WorldDefinition def) {
        Map<Ids.LocationId, Location> nodes = new LinkedHashMap<>();
        Map<Ids.LocationId, Production> production = new LinkedHashMap<>();
        Map<Ids.LocationId, Ids.LocationId> parents = new LinkedHashMap<>();
        ResourceLedger ledger = new ResourceLedger();

        // 1. 地点
        for (WorldDefinition.LocationDef d : def.locations()) {
            Ids.LocationId id = new Ids.LocationId(d.id());
            Stock stock = Stock.of(d.stock());
            Location loc = switch (d.kind()) {
                case "StarSystem" -> new Location.StarSystem(id, stock, d.name());
                case "Planet" -> new Location.Planet(id, stock, d.name());
                case "OrbitalStation" -> new Location.OrbitalStation(id, stock, d.name());
                case "Anomaly" -> new Location.Anomaly(id, stock, d.name());
                case "Colony" -> new Location.Colony(id, stock, d.name());
                case "Facility" -> new Location.Facility(id, stock, d.name());
                default -> throw new IllegalStateException("未知地点种类: " + d.kind());
            };
            nodes.put(id, loc);
            if (d.parentId() != null) {
                parents.put(id, new Ids.LocationId(d.parentId()));
            }
            if (d.production() != null) {
                production.put(id, new Production(
                        d.production().resource(), d.production().perDay()));
            }
            // 账本累加初始库存
            d.stock().forEach((res, amt) -> {
                if (amt.signum() > 0) {
                    ledger.credit(res, amt);
                }
            });
        }

        // 2. 舰船（作为移动 Location 进入图）
        for (WorldDefinition.ShipDef d : def.ships()) {
            Ids.ShipId shipId = new Ids.ShipId(d.id());
            Ids.LocationId locId = new Ids.LocationId(d.id() + 100_000); // 船的地点身份不与静态地点冲突
            Stock cargo = Stock.of(d.cargo());
            Location.Ship ship = new Location.Ship(
                    locId, Stock.empty(), d.name(), shipId,
                    new Ids.LocationId(d.at()), cargo, null);
            nodes.put(locId, ship);
            d.cargo().forEach((res, amt) -> {
                if (amt.signum() > 0) {
                    ledger.credit(res, amt);
                }
            });
        }

        // 2.5 主体（船员/居民/使者同一类型）
        java.util.Map<Ids.AgentId, fc.world.Agent> agents = new LinkedHashMap<>();
        for (WorldDefinition.AgentDef d : def.agents()) {
            Ids.AgentId aid = new Ids.AgentId(d.id());
            agents.put(aid, new fc.world.Agent(
                    aid,
                    fc.world.Agent.AgentRole.valueOf(d.role()),
                    fc.world.Trait.valueOf(d.trait()),
                    d.name(),
                    new Ids.LocationId(d.at()),
                    fc.world.Needs.INITIAL,
                    fc.world.Assignment.WORKING,
                    d.skill()));
        }

        // 3. 航线（M1 按无向处理：Navigation 双向可达）
        List<Link> links = new ArrayList<>();
        for (WorldDefinition.LinkDef d : def.links()) {
            Ids.LocationId from = new Ids.LocationId(d.from());
            Ids.LocationId to = new Ids.LocationId(d.to());
            Link link = switch (d.kind()) {
                case "WarpRoute" -> new Link.WarpRoute(from, to, d.ticks(), d.risk(), d.cost());
                case "SurfaceRoute" -> new Link.SurfaceRoute(from, to, d.ticks(), d.risk(), d.cost());
                case "InternalLane" -> new Link.InternalLane(from, to, d.ticks(), d.risk(), d.cost());
                default -> throw new IllegalStateException("未知航线种类: " + d.kind());
            };
            links.add(link);
        }

        LocationGraph graph = new LocationGraph(nodes, links);
        return new AssembledWorld(def.name(), graph, ledger, production, parents, agents,
                new Stardate(def.epochDay()), def.seed());
    }
}
