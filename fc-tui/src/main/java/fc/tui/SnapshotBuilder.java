package fc.tui;

import fc.history.query.EventView;
import fc.world.ColonyState;
import fc.world.Faction;
import fc.world.Ids;
import fc.world.KnowledgeState;
import fc.world.Location;
import fc.world.ResourceKind;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 从 {@link TuiGame} 当前状态构建只读 {@link Snapshot}。
 *
 * <p>构建发生在命令执行之后，渲染期间只读快照；地图、舰队、殖民地、科研、贸易
 * 与情报页面共享同一份数据，避免各页面重复扫描世界。</p>
 */
public final class SnapshotBuilder {

    private SnapshotBuilder() {
    }

    public static Snapshot build(TuiGame game) {
        if (game == null || game.state() == null) {
            throw new IllegalArgumentException("游戏状态不能为空");
        }
        WorldState state = game.state();
        Set<Ids.LocationId> playerPlaces = state.playerLocationIds();

        List<Snapshot.Place> places = new ArrayList<>();
        int known = 0;
        int unknown = 0;
        List<Location> nodes = new ArrayList<>(state.graph().nodes().values());
        nodes.sort(Comparator.comparingLong(location -> location.id().id()));
        for (Location location : nodes) {
            if (location instanceof Location.Ship) {
                continue;
            }
            KnowledgeState knowledge = state.knowledgeOf(location.id());
            if (knowledge == KnowledgeState.UNKNOWN) {
                unknown++;
            } else {
                known++;
            }
            List<String> links = new ArrayList<>();
            for (fc.world.Link link : state.graph().linksFrom(location.id())) {
                Ids.LocationId other = link.from().equals(location.id())
                        ? link.to() : link.from();
                links.add(nameOf(state, other));
            }
            Map<ResourceKind, BigDecimal> stock = new LinkedHashMap<>();
            for (ResourceKind kind : ResourceKind.GOODS) {
                BigDecimal amount = state.goods(location.id(), kind);
                if (amount != null && amount.signum() != 0) {
                    stock.put(kind, amount);
                }
            }
            Ids.FactionId ownerId = state.locationOwner(location.id());
            String owner = ownerId == null ? "无主" : factionName(state, ownerId);
            places.add(new Snapshot.Place(Long.toString(location.id().id()),
                    location.name(), kindOf(location), knowledge, owner, stock,
                    links, playerPlaces.contains(location.id())));
        }

        List<Snapshot.ShipView> ships = new ArrayList<>();
        for (Location.Ship ship : state.graph().ships()) {
            Location.VoyageProgress voyage = ship.voyage();
            String voyageText = voyage == null ? "停泊"
                    : nameOf(state, voyage.from()) + " " + "→" + " "
                            + nameOf(state, voyage.to()) + "（"
                            + voyage.elapsedTicks() + "/" + voyage.totalTicks() + "）";
            ships.add(new Snapshot.ShipView("S" + ship.shipId().id(), ship.name(),
                    ship.shipType(), nameOf(state, ship.parent()), ship.hull(),
                    ship.maxHull(), ship.crewCapacity(), voyageText,
                    ship.cargoUsed(), ship.cargoCapacity()));
        }

        List<Snapshot.ColonyView> colonies = new ArrayList<>();
        List<ColonyState> colonyStates = new ArrayList<>(state.colonies().values());
        colonyStates.sort(Comparator.comparingLong(c -> c.id().id()));
        for (ColonyState colony : colonyStates) {
            ColonyState.Upgrade upgrade = colony.nextUpgrade();
            String next = upgrade == null ? "已满级" : "升 " + upgrade.toLevel() + " 级："
                    + goodsText(upgrade.goods()) + "｜信用点 "
                    + plain(upgrade.credits());
            colonies.add(new Snapshot.ColonyView(Long.toString(colony.id().id()),
                    nameOf(state, colony.id()), colony.level(), colony.population(),
                    colony.playerOwned(), next));
        }

        List<Snapshot.FactionView> factions = new ArrayList<>();
        for (Faction faction : state.factions().values()) {
            Set<fc.world.Treaty> treaties = state.treaties().get(faction.id());
            List<String> names = new ArrayList<>();
            if (treaties != null) {
                for (fc.world.Treaty treaty : treaties) {
                    names.add(treaty.displayName());
                }
            }
            factions.add(new Snapshot.FactionView(faction.name(),
                    faction.kind().name(), state.reputation(faction.id()),
                    names.isEmpty() ? "无" : String.join("/", names)));
        }

        List<Snapshot.EventRow> events = new ArrayList<>();
        List<fc.world.GameEvent> all = state.chronicle().all();
        int from = Math.max(0, all.size() - 24);
        for (int i = from; i < all.size(); i++) {
            EventView view = EventView.of(all.get(i));
            events.add(new Snapshot.EventRow(view.day(), view.category(),
                    view.type(), view.summary()));
        }

        List<Snapshot.ResearchJob> jobs = new ArrayList<>();
        for (Map.Entry<Ids.LocationId, String> entry : state.researchJobs().entrySet()) {
            jobs.add(new Snapshot.ResearchJob(nameOf(state, entry.getKey()),
                    entry.getValue()));
        }

        List<Snapshot.DecisionView> decisions = new ArrayList<>();
        for (fc.world.PendingDecision decision : state.pendingDecisions()) {
            List<Snapshot.OptionView> options = new ArrayList<>();
            for (fc.world.PendingDecision.Option option : decision.options()) {
                options.add(new Snapshot.OptionView(option.id(), option.label()));
            }
            decisions.add(new Snapshot.DecisionView(decision.id().toString(),
                    decision.title(), decision.description(), options));
        }

        Map<ResourceKind, BigDecimal> resources = new LinkedHashMap<>();
        for (ResourceKind kind : ResourceKind.GOODS) {
            resources.put(kind, state.playerStock(kind));
        }

        return new Snapshot(state.now().day(), state.now().toIsoDate().toString(),
                plain(state.credits()), state.ending() == null ? "" : state.ending(),
                places, ships, colonies, factions, events,
                new ArrayList<>(new TreeSet<>(state.techs())), jobs, decisions,
                resources, game.marketReport(), game.researchReport(),
                game.technologiesReport(), known, unknown, game.goalsReport());
    }

    private static String kindOf(Location location) {
        return switch (location) {
            case Location.StarSystem s -> "恒星系";
            case Location.Planet p -> "行星";
            case Location.OrbitalStation s -> "空间站";
            case Location.Anomaly a -> "异常点";
            case Location.Colony c -> "殖民地";
            case Location.Facility f -> "设施";
            case Location.Ship s -> "舰船";
            case Location.ExtensionLocation e -> "扩展地点";
        };
    }

    private static String factionName(WorldState state, Ids.FactionId id) {
        Faction faction = state.faction(id);
        return faction == null ? "势力#" + id.id() : faction.name();
    }

    private static String nameOf(WorldState state, Ids.LocationId id) {
        if (id == null) {
            return "—";
        }
        Location location = state.location(id);
        return location == null ? "L" + id.id() : location.name();
    }

    private static String goodsText(Map<ResourceKind, BigDecimal> goods) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<ResourceKind, BigDecimal> entry : goods.entrySet()) {
            parts.add(entry.getKey().displayName() + " " + plain(entry.getValue()));
        }
        return parts.isEmpty() ? "无" : String.join(" / ", parts);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
