package fc.colony;

import fc.config.world.WorldDefinition;
import fc.sim.WorldAssembler;
import fc.sim.rng.RngStreams;
import fc.sim.tick.TickContext;
import fc.sim.tick.TimeStage;
import fc.sim.tick.VoyageStage;
import fc.testkit.FcTest;
import fc.testkit.TestRunner;
import fc.world.Agent;
import fc.world.Assignment;
import fc.world.ColonyState;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.MoneyLedger;
import fc.world.Needs;
import fc.world.ResourceKind;
import fc.world.SupplyLine;
import fc.world.Trait;
import fc.world.WorldState;
import fc.world.time.Stardate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * M3 殖民地与补给线测试：升级消耗、生活物资、人口增长、定期调运与守恒。
 */
public final class ColonyTest {

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(ColonyTest.class))) {
            System.exit(1);
        }
    }

    private static final class TestChronicle implements fc.world.Chronicle {
        private final List<GameEvent> events = new java.util.ArrayList<>();

        @Override
        public void record(GameEvent e) {
            events.add(e);
        }

        @Override
        public List<GameEvent> since(Stardate s) {
            return List.copyOf(events);
        }

        @Override
        public List<GameEvent> byActor(Ids.AgentId a) {
            return List.of();
        }

        @Override
        public List<GameEvent> at(Ids.LocationId l) {
            return List.copyOf(events);
        }

        @Override
        public List<GameEvent> all() {
            return List.copyOf(events);
        }

        @Override
        public int size() {
            return events.size();
        }
    }

    private static final class Fixture {
        final WorldAssembler.AssembledWorld world;
        final WorldState state;

        Fixture(WorldAssembler.AssembledWorld world, WorldState state) {
            this.world = world;
            this.state = state;
        }
    }

    /**
     * 地点 1 = 玩家前哨站（0 级），地点 2 = 非玩家殖民地。
     * 两者之间一条 2 天航线；可选货量与人口。
     */
    private static Fixture world(String foodAt1, String foodAt2, int level, String owner,
                                 int population) {
        List<WorldDefinition.LocationDef> locs = List.of(
                new WorldDefinition.LocationDef(1, "Colony", "前哨", null,
                        Map.of("food", new BigDecimal(foodAt1),
                                "water", new BigDecimal("30"),
                                "ore", new BigDecimal("20")),
                        new BigDecimal("100"), null, level, owner, population,
                        List.of(new WorldDefinition.UpgradeDef(1,
                                        Map.of("ore", new BigDecimal("10")), new BigDecimal("50")),
                                new WorldDefinition.UpgradeDef(2,
                                        Map.of("ore", new BigDecimal("15")), new BigDecimal("80")))),
                new WorldDefinition.LocationDef(2, "Colony", "邻镇", null,
                        Map.of("food", new BigDecimal(foodAt2)), new BigDecimal("100"),
                        null, 1, "faction:1", 20, List.of()));
        List<WorldDefinition.LinkDef> links = List.of(
                new WorldDefinition.LinkDef(1, 2, "SurfaceRoute", 2, 0.0, BigDecimal.ONE));
        List<WorldDefinition.AgentDef> agents = List.of(
                new WorldDefinition.AgentDef(1, "居民", "RESIDENT", 1, "STOIC", 2));
        WorldDefinition def = new WorldDefinition("c", 0, locs, links, List.of(), agents,
                List.of(), new WorldDefinition.EconomyDef(List.of(), List.of(),
                        new BigDecimal("500"), new BigDecimal("1000"), BigDecimal.ZERO),
                WorldDefinition.DEFAULT_SEED);
        var w = WorldAssembler.assemble(def);
        WorldState st = new WorldState(w.graph(), w.agents(), w.ledger(), w.money(),
                new TestChronicle(), w.epoch(), w.parents());
        w.factions().values().forEach(st::addFaction);
        w.colonies().values().forEach(st::addColony);
        // 材料充足，让升级/生活测试聚焦于被测行为
        st.produce(new Ids.LocationId(1), ResourceKind.FUEL, new BigDecimal("50"), "测试");
        return new Fixture(w, st);
    }

    private static TickContext ctx() {
        return new TickContext(Map.of(), RngStreams.of(1));
    }

    // ------------------------------------------------------------ 升级

    @FcTest
    public void upgradeConsumesGoodsAndCredits() {
        Fixture f = world("30", "30", 0, "player", 20);
        WorldState st = f.state;
        BigDecimal moneyBefore = st.money().total();

        var r = ColonyService.upgrade(st, new Ids.LocationId(1));
        assert r.ok() : r.message();
        assert st.colony(new Ids.LocationId(1)).level() == 1;
        assert st.goods(new Ids.LocationId(1), ResourceKind.ORE)
                .compareTo(BigDecimal.TEN) == 0 : "应消耗 10 ore";
        assert st.credits().compareTo(new BigDecimal("450")) == 0 : "玩家应支付 50 信用点";
        assert st.money().balance(MoneyLedger.locationAccount(new Ids.LocationId(1)))
                .compareTo(new BigDecimal("150")) == 0 : "资金转入殖民地账户";
        assert st.money().total().compareTo(moneyBefore) == 0 : "资金总量守恒";
        assert st.chronicle().all().stream().anyMatch(e -> e instanceof GameEvent.ColonyUpgraded);
        st.verifyLedger();
    }

    @FcTest
    public void upgradeRejectedWhenGoodsMissing() {
        Fixture f = world("30", "30", 0, "player", 20);
        WorldState st = f.state;
        st.consume(new Ids.LocationId(1), ResourceKind.ORE, new BigDecimal("15"), "测试");
        var r = ColonyService.upgrade(st, new Ids.LocationId(1));
        assert !r.ok();
        assert st.colony(new Ids.LocationId(1)).level() == 0 : "失败不得改变等级";
        assert st.goods(new Ids.LocationId(1), ResourceKind.ORE)
                .compareTo(new BigDecimal("5")) == 0 : "失败不得扣料";
    }

    @FcTest
    public void upgradeRejectedForNonPlayerColony() {
        Fixture f = world("30", "30", 1, "faction:1", 20);
        WorldState st = f.state;
        var r = ColonyService.upgrade(st, new Ids.LocationId(1));
        assert !r.ok();
        assert r.message().contains("玩家");
    }

    // ------------------------------------------------------------ 生活与人口

    @FcTest
    public void colonyConsumesFoodAndWater() {
        Fixture f = world("20", "30", 1, "player", 30);
        WorldState st = f.state;
        new ColonySystemImpl().onTick(st, ctx());
        // pop=30 → 每日 6 食物 + 6 水
        assert st.goods(new Ids.LocationId(1), ResourceKind.FOOD)
                .compareTo(new BigDecimal("14")) == 0;
        assert st.goods(new Ids.LocationId(1), ResourceKind.WATER)
                .compareTo(new BigDecimal("24")) == 0;
    }

    @FcTest
    public void populationGrowsOnSevenDayCycle() {
        Fixture f = world("30", "30", 1, "player", 30);
        WorldState st = f.state;
        st.advanceTo(new Stardate(6)); // (6 + 1) % 7 == 0
        new ColonySystemImpl().onTick(st, ctx());
        assert st.colony(new Ids.LocationId(1)).population() == 31;
    }

    @FcTest
    public void foodShortageLowersMoraleAndReports() {
        Fixture f = world("0", "30", 1, "player", 30);
        WorldState st = f.state;
        Agent a = st.agents().values().iterator().next();
        int before = a.needs().morale();
        TickContext c = ctx();
        new ColonySystemImpl().onTick(st, c);
        assert a.needs().morale() < before : "缺粮应降低士气";
        assert c.reportLines().stream().anyMatch(s -> s.contains("缺粮"))
                : c.reportLines().toString();
    }

    // ------------------------------------------------------------ 补给线

    @FcTest
    public void supplyLineDispatchesAndConserves() {
        Fixture f = world("30", "100", 1, "player", 20);
        WorldState st = f.state;
        BigDecimal totalBefore = st.ledger().total(ResourceKind.FOOD);

        var r = LogisticsService.establish(st, "L1", new Ids.LocationId(2),
                new Ids.LocationId(1), ResourceKind.FOOD, new BigDecimal("5"), 2);
        assert r.ok() : r.message();
        SupplyLine line = st.supplyLines().get(new Ids.SupplyLineId("L1"));
        assert line != null && line.travelTicks() == 2;

        // 第一天到期发车
        TickContext c1 = ctx();
        new TimeStage().apply(st, c1);
        new VoyageStage().apply(st, c1);
        assert line.deliveredRuns() == 1 : "首日应发车";
        assert st.goods(new Ids.LocationId(2), ResourceKind.FOOD)
                .compareTo(new BigDecimal("95")) == 0 : "货物进入在途分区";
        assert st.ledger().total(ResourceKind.FOOD).compareTo(totalBefore) == 0
                : "发货后世界总量守恒";

        // 第 2、3 天推进；第 3 天到货
        for (int i = 0; i < 2; i++) {
            TickContext c = ctx();
            new TimeStage().apply(st, c);
            new VoyageStage().apply(st, c);
        }
        assert st.goods(new Ids.LocationId(1), ResourceKind.FOOD)
                .compareTo(new BigDecimal("35")) == 0 : "前哨应收到 5 食物";
        assert st.ledger().total(ResourceKind.FOOD).compareTo(totalBefore) == 0;
        st.verifyLedger();
    }

    @FcTest
    public void supplyLineSkipsWhenSourceShort() {
        Fixture f = world("30", "3", 1, "player", 20);
        WorldState st = f.state;
        var r = LogisticsService.establish(st, "L1", new Ids.LocationId(2),
                new Ids.LocationId(1), ResourceKind.FOOD, new BigDecimal("5"), 1);
        assert r.ok() : r.message();

        TickContext c = ctx();
        new TimeStage().apply(st, c);
        new VoyageStage().apply(st, c);

        SupplyLine line = st.supplyLines().get(new Ids.SupplyLineId("L1"));
        assert line.deliveredRuns() == 0 : "货源不足应跳过";
        assert st.activeTransfers().isEmpty();
        assert c.reportLines().stream().anyMatch(s -> s.contains("货源不足"))
                : c.reportLines().toString();
    }

    @FcTest
    public void supplyLineCancelStopsDispatch() {
        Fixture f = world("30", "100", 1, "player", 20);
        WorldState st = f.state;
        LogisticsService.establish(st, "L1", new Ids.LocationId(2),
                new Ids.LocationId(1), ResourceKind.FOOD, new BigDecimal("5"), 1);
        var cancel = LogisticsService.cancel(st, "L1");
        assert cancel.ok() : cancel.message();

        TickContext c = ctx();
        new TimeStage().apply(st, c);
        new VoyageStage().apply(st, c);
        assert st.activeTransfers().isEmpty();
    }

    @FcTest
    public void supplyLineRejectsBadArguments() {
        Fixture f = world("30", "100", 1, "player", 20);
        WorldState st = f.state;
        assert !LogisticsService.establish(st, "", new Ids.LocationId(2),
                new Ids.LocationId(1), ResourceKind.FOOD, BigDecimal.ONE, 1).ok();
        assert !LogisticsService.establish(st, "L1", new Ids.LocationId(1),
                new Ids.LocationId(1), ResourceKind.FOOD, BigDecimal.ONE, 1).ok();
        assert !LogisticsService.establish(st, "L2", new Ids.LocationId(2),
                new Ids.LocationId(1), ResourceKind.FOOD, BigDecimal.ZERO, 1).ok();
        assert !LogisticsService.establish(st, "L3", new Ids.LocationId(2),
                new Ids.LocationId(1), ResourceKind.FOOD, BigDecimal.ONE, 0).ok();
        assert !LogisticsService.establish(st, "L4", new Ids.LocationId(99),
                new Ids.LocationId(1), ResourceKind.FOOD, BigDecimal.ONE, 1).ok();
    }

    @FcTest
    public void colonyStateRejectsInvalidLevel() {
        boolean threw = false;
        try {
            new ColonyState(new Ids.LocationId(1), true, 4, 10, List.of());
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assert threw;
    }
}
