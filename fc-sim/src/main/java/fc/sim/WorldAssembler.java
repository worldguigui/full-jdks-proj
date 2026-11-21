package fc.sim;

import fc.config.world.WorldDefinition;
import fc.world.Agent;
import fc.world.ColonyState;
import fc.world.Faction;
import fc.world.Ids;
import fc.world.Link;
import fc.world.Location;
import fc.world.LocationGraph;
import fc.world.MoneyLedger;
import fc.world.ResourceKind;
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
 * <p>这是「世界数据全部来自配置」的落地点——核心代码不含任何地点/航线硬编码。
 * 装配同时开立权威货物账本分区与财务账户，并建立势力、殖民地与关系矩阵。</p>
 */
public final class WorldAssembler {

    private WorldAssembler() {
    }

    /**
     * 装配结果：世界图 + 货物/财务账本 + 每日产出表 + 父子层级 + 主体 +
     * 势力/殖民地 + 启程时间 + 随机种子。
     */
    public record AssembledWorld(
            String name,
            LocationGraph graph,
            ResourceLedger ledger,
            MoneyLedger money,
            Map<Ids.LocationId, Production> production,
            Map<Ids.LocationId, Ids.LocationId> parents,
            Map<Ids.AgentId, Agent> agents,
            Map<Ids.FactionId, Faction> factions,
            Map<Ids.LocationId, ColonyState> colonies,
            Map<String, Integer> initialRelations,
            Stardate epoch,
            long seed,
            BigDecimal playerCredits,
            BigDecimal loanDailyRate,
            Map<String, WorldDefinition.ShipTypeDef> shipTypes,
            List<WorldDefinition.EventDef> events,
            Map<Ids.LocationId, Ids.FactionId> owners,
            Map<Ids.LocationId, fc.world.KnowledgeState> knowledge,
            Map<Ids.LocationId, Integer> workerCapacity,
            Map<Ids.LocationId, String> facilityKinds) {
    }

    /** 每日产出。 */
    public record Production(ResourceKind resource, BigDecimal perDay) {
    }

    /** 从世界定义装配。 */
    public static AssembledWorld assemble(WorldDefinition def) {
        Map<Ids.LocationId, Location> nodes = new LinkedHashMap<>();
        Map<Ids.LocationId, Production> production = new LinkedHashMap<>();
        Map<Ids.LocationId, Ids.LocationId> parents = new LinkedHashMap<>();
        ResourceLedger ledger = new ResourceLedger();
        MoneyLedger money = new MoneyLedger();

        // 0. 财务基础账户
        money.open(MoneyLedger.PLAYER, def.economy().playerCredits());
        money.open(MoneyLedger.BANK, def.economy().bankCapital());
        money.open(MoneyLedger.HOUSEHOLDS, BigDecimal.ZERO);
        money.open(MoneyLedger.TREASURY, BigDecimal.ZERO);

        Map<Ids.LocationId, ColonyState> colonies = new LinkedHashMap<>();
        Map<Ids.LocationId, Ids.FactionId> owners = new LinkedHashMap<>();
        Map<Ids.LocationId, fc.world.KnowledgeState> knowledge = new LinkedHashMap<>();
        Map<Ids.LocationId, Integer> workerCapacity = new LinkedHashMap<>();
        Map<Ids.LocationId, String> facilityKinds = new LinkedHashMap<>();

        // 1. 地点
        for (WorldDefinition.LocationDef d : def.locations()) {
            Ids.LocationId id = new Ids.LocationId(d.id());
            Stock stock = Stock.of(convert(d.stock(), "地点 " + d.id() + " 库存"));
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
            ledger.open(id, stock);
            money.open(MoneyLedger.locationAccount(id), d.credits());
            if (d.parentId() != null) {
                parents.put(id, new Ids.LocationId(d.parentId()));
            }
            if (d.production() != null) {
                production.put(id, new Production(
                        ResourceKind.goodsFromId(d.production().resource()),
                        d.production().perDay()));
            }
            if (d.isColony()) {
                List<ColonyState.Upgrade> upgrades = new ArrayList<>();
                for (WorldDefinition.UpgradeDef u : d.upgrades()) {
                    Map<ResourceKind, BigDecimal> goods = new LinkedHashMap<>();
                    u.goods().forEach((k, v) -> goods.put(ResourceKind.goodsFromId(k), v));
                    upgrades.add(new ColonyState.Upgrade(u.toLevel(), goods, u.credits()));
                }
                boolean playerOwned = "player".equals(d.owner());
                colonies.put(id, new ColonyState(id, playerOwned, d.colonyLevel(),
                        d.population(), upgrades));
            }
            if (d.owner() != null && d.owner().startsWith("faction:")) {
                owners.put(id, new Ids.FactionId(
                        Long.parseLong(d.owner().substring("faction:".length()))));
            }
            if (d.initialKnowledge() != null) {
                knowledge.put(id, fc.world.KnowledgeState.valueOf(d.initialKnowledge()));
            } else if ("player".equals(d.owner())
                    || (d.isFacility() && d.parentId() != null)) {
                knowledge.put(id, fc.world.KnowledgeState.SURVEYED);
            } else if (d.isColony() && !"player".equals(d.owner())) {
                knowledge.put(id, fc.world.KnowledgeState.SURVEYED);
            }
            if (d.workerCapacity() > 0) {
                workerCapacity.put(id, d.workerCapacity());
            }
            if (d.facilityKind() != null && !d.facilityKind().isBlank()) {
                facilityKinds.put(id, d.facilityKind());
            }
        }

        // 2. 舰船（作为移动 Location 进入图）
        Map<String, WorldDefinition.ShipTypeDef> shipTypes = new LinkedHashMap<>();
        for (WorldDefinition.ShipTypeDef t : def.shipTypes()) {
            shipTypes.put(t.id(), t);
        }
        Map<Ids.LocationId, Integer> crewOnboard = new LinkedHashMap<>();
        for (WorldDefinition.ShipDef d : def.ships()) {
            Ids.ShipId shipId = new Ids.ShipId(d.id());
            Ids.LocationId locId = new Ids.LocationId(d.id() + 100_000);
            Stock cargo = Stock.of(convert(d.cargo(), "舰船 " + d.id() + " 货舱"));
            WorldDefinition.ShipTypeDef type = d.type() == null
                    ? null : shipTypes.get(d.type());
            if (d.type() != null && type == null) {
                throw new IllegalStateException("舰船 " + d.id() + " 的船型未装配: " + d.type());
            }
            String typeId = type == null ? "freighter" : type.id();
            BigDecimal capacity = type == null
                    ? Location.Ship.DEFAULT_CARGO_CAPACITY : type.cargoCapacity();
            int crewCap = type == null
                    ? Location.Ship.DEFAULT_CREW_CAPACITY : type.crewCapacity();
            int maxHull = type == null ? Location.Ship.DEFAULT_MAX_HULL : type.maxHull();
            double risk = type == null ? 0.0 : type.baseRiskFactor();
            int hull = d.hull() > 0 ? Math.min(d.hull(), maxHull) : maxHull;
            BigDecimal used = BigDecimal.ZERO;
            for (BigDecimal v : cargo.amounts().values()) {
                used = used.add(v);
            }
            if (used.compareTo(capacity) > 0) {
                throw new IllegalStateException("舰船 " + d.id() + " 初始货舱超载: "
                        + used + " > " + capacity);
            }
            Location.Ship ship = new Location.Ship(
                    locId, d.name(), shipId, typeId, capacity, crewCap, maxHull, risk,
                    new Ids.LocationId(d.at()), cargo, null, hull);
            nodes.put(locId, ship);
            ledger.open(locId, cargo);
            crewOnboard.put(locId, 0);
            knowledge.putIfAbsent(new Ids.LocationId(d.at()),
                    fc.world.KnowledgeState.SURVEYED);
        }

        // 3. 主体（船员/居民/使者同一类型）
        Map<Ids.AgentId, Agent> agents = new LinkedHashMap<>();
        for (WorldDefinition.AgentDef d : def.agents()) {
            Ids.AgentId aid = new Ids.AgentId(d.id());
            Ids.LocationId at = new Ids.LocationId(d.at());
            Location loc = nodes.get(at);
            if (loc == null) {
                throw new IllegalStateException("主体 " + d.id() + " 的所在地点未装配: " + at);
            }
            if (loc instanceof Location.Ship ship) {
                int count = crewOnboard.merge(at, 1, Integer::sum);
                if (count > ship.crewCapacity()) {
                    throw new IllegalStateException("舰船 " + ship.name()
                            + " 船员铺位不足：" + count + " > " + ship.crewCapacity());
                }
            }
            agents.put(aid, new Agent(
                    aid,
                    Agent.AgentRole.valueOf(d.role()),
                    fc.world.Trait.valueOf(d.trait()),
                    d.name(),
                    at,
                    fc.world.Needs.INITIAL,
                    fc.world.Assignment.WORKING,
                    d.skills()));
        }

        // 4. 势力（财政账户 + 关系）
        Map<Ids.FactionId, Faction> factions = new LinkedHashMap<>();
        Map<String, Integer> relations = new LinkedHashMap<>();
        for (WorldDefinition.FactionDef d : def.factions()) {
            Ids.FactionId fid = new Ids.FactionId(d.id());
            Faction f = new Faction(fid, d.name(), Faction.Kind.valueOf(d.kind()),
                    new Ids.LocationId(d.seat()), d.doctrine(),
                    ResourceKind.goodsFromId(d.priority()), d.reserve(), d.stockTarget(),
                    d.tradeUnitPrice(), d.taxRate());
            factions.put(fid, f);
            money.open(f.account(), d.reserve());
            for (WorldDefinition.RelationDef r : d.relations()) {
                long low = Math.min(d.id(), r.other());
                long high = Math.max(d.id(), r.other());
                relations.put(low + ":" + high, r.value());
            }
        }
        for (WorldDefinition.FactionDef d : def.factions()) {
            for (WorldDefinition.RelationDef r : d.relations()) {
                if (!factions.containsKey(new Ids.FactionId(r.other()))) {
                    throw new IllegalStateException("势力关系对象未装配: " + r.other());
                }
            }
        }

        // 5. 航线
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
        return new AssembledWorld(def.name(), graph, ledger, money, production, parents, agents,
                factions, colonies, relations, new Stardate(def.epochDay()), def.seed(),
                def.economy().playerCredits(), def.economy().loanDailyRate(),
                shipTypes, def.events(), owners, knowledge, workerCapacity, facilityKinds);
    }

    private static Map<ResourceKind, BigDecimal> convert(Map<String, BigDecimal> raw, String what) {
        Map<ResourceKind, BigDecimal> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            try {
                out.put(ResourceKind.goodsFromId(k), v);
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException(what + " 含非法资源: " + k);
            }
        });
        return out;
    }
}
