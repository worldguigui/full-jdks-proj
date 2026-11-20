package fc.sim;

import fc.config.world.WorldDefinition;
import fc.sim.rng.RngStreams;
import fc.sim.tick.TickPipeline;
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
 * M1 装配与 Tick 管线测试：世界来自配置、十二阶段固定顺序、库存产出、航行推进。
 */
public final class M1TickTest {

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(M1TickTest.class))) {
            System.exit(1);
        }
    }

    /** 小型世界定义（3 地点 + 2 航线 + 1 船），完全由代码构造以隔离 XML 依赖。 */
    private static WorldDefinition tinyWorld() {
        List<WorldDefinition.LocationDef> locs = List.of(
                new WorldDefinition.LocationDef(1, "StarSystem", "A", null,
                        Map.of("fuel", new BigDecimal("50")), null),
                new WorldDefinition.LocationDef(2, "Planet", "B", 1L,
                        Map.of("ore", new BigDecimal("10")),
                        new WorldDefinition.ProductionDef("ore", new BigDecimal("5"))),
                new WorldDefinition.LocationDef(3, "OrbitalStation", "C", 2L,
                        Map.of(), null));
        List<WorldDefinition.LinkDef> links = List.of(
                new WorldDefinition.LinkDef(1, 2, "SurfaceRoute", 2, 0.0, new BigDecimal("2")),
                new WorldDefinition.LinkDef(2, 3, "InternalLane", 1, 0.0, new BigDecimal("1")));
        List<WorldDefinition.ShipDef> ships = List.of(
                new WorldDefinition.ShipDef(9, "T", 1, Map.of("fuel", new BigDecimal("20"))));
        return new WorldDefinition("tiny", 0, locs, links, ships);
    }

    /** 测试用内存编年史（fc-sim 不依赖 fc-history，故在此内联实现）。 */
    private static final class TestChronicle implements fc.world.Chronicle {
        private final java.util.List<GameEvent> events = new java.util.ArrayList<>();

        @Override
        public void record(GameEvent e) {
            events.add(e);
        }

        @Override
        public java.util.List<GameEvent> since(Stardate start) {
            return events.stream().filter(e -> !e.when().isBefore(start)).toList();
        }

        @Override
        public java.util.List<GameEvent> byActor(Ids.AgentId actor) {
            return events.stream().filter(e -> e.who().contains(actor)).toList();
        }

        @Override
        public java.util.List<GameEvent> at(Ids.LocationId location) {
            return events.stream().filter(e -> e.where().equals(location)).toList();
        }

        @Override
        public java.util.List<GameEvent> all() {
            return java.util.List.copyOf(events);
        }

        @Override
        public int size() {
            return events.size();
        }
    }

    private static WorldState stateOf(WorldAssembler.AssembledWorld w) {
        return new WorldState(w.graph(), Map.of(), w.ledger(),
                new TestChronicle(), w.epoch(), w.parents());
    }

    @FcTest
    public void assemblesFromDefinition() {
        var w = WorldAssembler.assemble(tinyWorld());
        assert w.graph().placeCount() == 3 : "地点数应为 3，实际 " + w.graph().placeCount();
        assert w.graph().links().size() == 2;
        assert w.graph().ships().size() == 1;
        assert "tiny".equals(w.name());
        w.ledger().verify(w.graph().nodes().values().stream()
                .collect(java.util.stream.Collectors.toMap(Location::id, Location::stock)));
    }

    @FcTest
    public void ledgerSeededFromStocks() {
        var w = WorldAssembler.assemble(tinyWorld());
        // 地点 50 fuel + 10 ore，船 20 fuel = 70 fuel / 10 ore
        assert w.ledger().total(ResourceKind.FUEL).compareTo(new BigDecimal("70")) == 0
                : "fuel=" + w.ledger().total(ResourceKind.FUEL);
        assert w.ledger().total(ResourceKind.ORE).compareTo(new BigDecimal("10")) == 0;
    }

    @FcTest
    public void pipelineHasTwelveStagesInFixedOrder() {
        var w = WorldAssembler.assemble(tinyWorld());
        var p = TickPipeline.m3(w.production(), RngStreams.of(42), null,
                null, null, null, null, BigDecimal.ZERO);
        List<String> expected = List.of(
                "1-time", "2-environment", "3-faction", "4-perceive", "5-decide", "6-work",
                "7-voyage", "8-consumption", "9-market", "10-event", "11-chronicle", "12-report");
        assert p.stageNames().equals(expected) : p.stageNames().toString();
    }

    @FcTest
    public void timeAdvancesOneDayPerTick() {
        var w = WorldAssembler.assemble(tinyWorld());
        WorldState st = stateOf(w);
        TickPipeline.m3(w.production(), RngStreams.of(42), null,
                null, null, null, null, BigDecimal.ZERO).tick(st);
        assert st.now().day() == 1 : "应为第 1 天，实际 " + st.now().day();
    }

    @FcTest
    public void inventoryAccruesProductionAndLedgerStaysConsistent() {
        var w = WorldAssembler.assemble(tinyWorld());
        WorldState st = stateOf(w);
        TickPipeline p = TickPipeline.m3(w.production(), RngStreams.of(42), null,
                null, null, null, null, BigDecimal.ZERO);
        for (int i = 0; i < 3; i++) {
            p.tick(st);
        }
        BigDecimal ore = st.goods(new Ids.LocationId(2), ResourceKind.ORE);
        assert ore.compareTo(new BigDecimal("10")) > 0 : "应有产出，实际 " + ore;
        assert ore.compareTo(new BigDecimal("25")) <= 0 : "最多 10+15，实际 " + ore;
        assert st.ledger().total(ResourceKind.ORE).compareTo(ore) == 0
                : "账本应与库存一致：账本 " + st.ledger().total(ResourceKind.ORE) + " 库 " + ore;
        st.verifyLedger();
    }

    @FcTest
    public void shipVoyageAdvancesAndArrives() {
        var w = WorldAssembler.assemble(tinyWorld());
        WorldState st = stateOf(w);
        Location.Ship ship = st.graph().ships().get(0);
        ship.beginVoyage(new Location.VoyageProgress(
                new Ids.LocationId(1), new Ids.LocationId(3), 0, 3, st.now()));
        TickPipeline p = TickPipeline.m3(w.production(), RngStreams.of(42), null,
                null, null, null, null, BigDecimal.ZERO);
        p.tick(st);
        p.tick(st);
        assert ship.inVoyage() : "2 天后仍在航";
        p.tick(st);
        assert !ship.inVoyage() : "3 天后应抵达";
        assert ship.parent().id() == 3 : "应在 3 号地点，实际 " + ship.parent().id();
        assert st.chronicle().size() == 1 : "应记录 1 条抵达事件";
    }

    @FcTest
    public void stardateFromConfigEpoch() {
        List<WorldDefinition.LocationDef> locs = List.of(
                new WorldDefinition.LocationDef(1, "StarSystem", "A", null, Map.of(), null));
        var def = new WorldDefinition("epoch", 100, locs, List.of(), List.of());
        var w = WorldAssembler.assemble(def);
        WorldState st = stateOf(w);
        assert st.now().day() == 100 : "启程日应为配置的 100，实际 " + st.now().day();
        assert st.now().equals(new Stardate(100));
    }

    @FcTest
    public void parentLocationsAreIndexed() {
        var w = WorldAssembler.assemble(tinyWorld());
        WorldState st = stateOf(w);
        assert new Ids.LocationId(1).equals(st.parentOf(new Ids.LocationId(2)));
        assert st.childrenOf(new Ids.LocationId(1)).equals(List.of(new Ids.LocationId(2)));
    }
}
