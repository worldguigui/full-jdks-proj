package fc.fleet;

import fc.config.world.WorldDefinition;
import fc.sim.WorldAssembler;
import fc.testkit.FcTest;
import fc.testkit.TestRunner;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Location;
import fc.world.ResourceKind;
import fc.world.WorldState;
import fc.world.time.Stardate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * M1 航行测试：最短路、多跳路线、燃料扣减与不足拒绝。
 */
public final class VoyageTest {

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(VoyageTest.class))) {
            System.exit(1);
        }
    }

    /** 1 -2- 2 -3- 3 链式世界；船在 1，燃料可调。 */
    private static WorldAssembler.AssembledWorld chainWorld(String fuel) {
        List<WorldDefinition.LocationDef> locs = List.of(
                new WorldDefinition.LocationDef(1, "StarSystem", "A", null, Map.of(), null),
                new WorldDefinition.LocationDef(2, "StarSystem", "B", null, Map.of(), null),
                new WorldDefinition.LocationDef(3, "StarSystem", "C", null, Map.of(), null));
        List<WorldDefinition.LinkDef> links = List.of(
                new WorldDefinition.LinkDef(1, 2, "WarpRoute", 2, 0.0, new BigDecimal("5")),
                new WorldDefinition.LinkDef(2, 3, "WarpRoute", 3, 0.0, new BigDecimal("7")));
        List<WorldDefinition.ShipDef> ships = List.of(
                new WorldDefinition.ShipDef(1, "S", 1, Map.of("fuel", new BigDecimal(fuel))));
        return WorldAssembler.assemble(new WorldDefinition("chain", 0, locs, links, ships));
    }

    private static WorldState stateOf(WorldAssembler.AssembledWorld w) {
        return new WorldState(w.graph(), Map.of(), w.ledger(), w.money(),
                new MiniChronicle(), w.epoch(), w.parents());
    }

    private static final class MiniChronicle implements fc.world.Chronicle {
        private final java.util.List<GameEvent> e = new java.util.ArrayList<>();

        public void record(GameEvent ev) {
            e.add(ev);
        }

        public java.util.List<GameEvent> since(Stardate s) {
            return java.util.List.copyOf(e);
        }

        public java.util.List<GameEvent> byActor(Ids.AgentId a) {
            return java.util.List.of();
        }

        public java.util.List<GameEvent> at(Ids.LocationId l) {
            return java.util.List.of();
        }

        public java.util.List<GameEvent> all() {
            return java.util.List.copyOf(e);
        }

        public int size() {
            return e.size();
        }
    }

    @FcTest
    public void shortestPathIsMultiHop() {
        var w = chainWorld("100");
        var route = Navigation.find(w.graph(), new Ids.LocationId(1), new Ids.LocationId(3));
        assert route.isPresent();
        assert route.get().totalTicks() == 5 : "2+3=5，实际 " + route.get().totalTicks();
        assert route.get().totalCost().compareTo(new BigDecimal("12")) == 0 : "5+7=12";
        assert route.get().links().size() == 2;
    }

    @FcTest
    public void shortestPathIsSymmetric() {
        var w = chainWorld("100");
        var route = Navigation.find(w.graph(), new Ids.LocationId(3), new Ids.LocationId(1));
        assert route.isPresent();
        assert route.get().totalTicks() == 5 : "无向图应可反向";
    }

    @FcTest
    public void sailConsumesFuelThroughLedger() {
        var w = chainWorld("100");
        WorldState st = stateOf(w);
        Location.Ship ship = st.graph().ships().get(0);
        BigDecimal worldBefore = st.ledger().total(ResourceKind.FUEL);
        var r = VoyageService.sail(st, ship, 3);
        assert r.ok() : r.message();
        assert r.ticks() == 5 : "应为 5 天";
        assert ship.cargo().amount(ResourceKind.FUEL).compareTo(new BigDecimal("88")) == 0
                : "100-12=88，实际 " + ship.cargo().amount(ResourceKind.FUEL);
        assert st.ledger().total(ResourceKind.FUEL)
                .compareTo(worldBefore.subtract(new BigDecimal("12"))) == 0
                : "燃料经账本扣除（世界总量减少）";
        assert ship.inVoyage();
        assert st.chronicle().size() == 2 : "应写燃料消耗 + 启航两条事件";
    }

    @FcTest
    public void sailRejectedWhenFuelInsufficient() {
        var w = chainWorld("5");
        WorldState st = stateOf(w);
        Location.Ship ship = st.graph().ships().get(0);
        var r = VoyageService.sail(st, ship, 3);
        assert !r.ok() : "燃料不足应拒绝";
        assert r.message().contains("燃料不足") : r.message();
        assert !ship.inVoyage() : "不应进入在航";
        assert ship.cargo().amount(ResourceKind.FUEL).compareTo(new BigDecimal("5")) == 0 : "燃料不应被扣";
    }

    @FcTest
    public void sailRejectedWhenAlreadyInVoyage() {
        var w = chainWorld("100");
        WorldState st = stateOf(w);
        Location.Ship ship = st.graph().ships().get(0);
        assert VoyageService.sail(st, ship, 2).ok();
        var second = VoyageService.sail(st, ship, 3);
        assert !second.ok() : "在航中不应允许再次启航";
    }

    @FcTest
    public void sailRejectedWhenSameLocation() {
        var w = chainWorld("100");
        WorldState st = stateOf(w);
        Location.Ship ship = st.graph().ships().get(0);
        var r = VoyageService.sail(st, ship, 1);
        assert !r.ok();
        assert r.message().contains("已在该地点");
    }

    @FcTest
    public void sailRejectedWhenUnreachable() {
        List<WorldDefinition.LocationDef> locs = List.of(
                new WorldDefinition.LocationDef(1, "StarSystem", "A", null, Map.of(), null),
                new WorldDefinition.LocationDef(2, "StarSystem", "B", null, Map.of(), null));
        List<WorldDefinition.ShipDef> ships = List.of(
                new WorldDefinition.ShipDef(1, "S", 1, Map.of("fuel", new BigDecimal("100"))));
        var w = WorldAssembler.assemble(new WorldDefinition("iso", 0, locs, List.of(), ships));
        WorldState st = stateOf(w);
        var r = VoyageService.sail(st, st.graph().ships().get(0), 2);
        assert !r.ok();
        assert r.message().contains("无可用航路") : r.message();
    }
}
