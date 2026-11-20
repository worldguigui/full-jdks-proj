package fc.economy;

import fc.config.world.RecipeDef;
import fc.config.world.ResourceDef;
import fc.config.world.WorldDefinition;
import fc.sim.WorldAssembler;
import fc.sim.rng.RngStreams;
import fc.sim.tick.ConsumptionStage;
import fc.sim.tick.TickContext;
import fc.sim.tick.WorkStage;
import fc.testkit.FcTest;
import fc.testkit.TestRunner;
import fc.world.Agent;
import fc.world.Assignment;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Location;
import fc.world.MoneyLedger;
import fc.world.Needs;
import fc.world.ResourceKind;
import fc.world.Trait;
import fc.world.WorkUnit;
import fc.world.WorldState;
import fc.world.time.Stardate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M3 经济测试：供需定价、价格冲击、交易守恒、部分成交、配方生产、工资与贷款利息。
 */
public final class EconomyTest {

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(EconomyTest.class))) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ 工具

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

    private static final ResourceDef ORE = new ResourceDef("ore", new BigDecimal("3"),
            new BigDecimal("100"), 1.0);
    private static final ResourceDef METAL = new ResourceDef("metal", new BigDecimal("8"),
            new BigDecimal("80"), 1.0);

    private static final class Fixture {
        final WorldAssembler.AssembledWorld world;
        final WorldState state;
        final Economy economy;

        Fixture(WorldAssembler.AssembledWorld world, WorldState state, Economy economy) {
            this.world = world;
            this.state = state;
            this.economy = economy;
        }
    }

    /**
     * 单地点世界：地点 1 有 ore/food/water 与可调信用点，可选一把工作主体与舰船。
     */
    private static Fixture world(String ore, String credits, List<RecipeDef> recipes,
                                 boolean worker, boolean withShip) {
        return world(ore, credits, recipes, worker ? 1 : 0, withShip);
    }

    private static Fixture world(String ore, String credits, List<RecipeDef> recipes,
                                 int workers, boolean withShip) {
        List<WorldDefinition.LocationDef> locs = List.of(
                new WorldDefinition.LocationDef(1, "Colony", "C", null,
                        Map.of("ore", new BigDecimal(ore),
                                "food", new BigDecimal("20"),
                                "water", new BigDecimal("20")),
                        new BigDecimal(credits), null, 1, "player", 30, List.of()));
        List<WorldDefinition.ShipDef> ships = withShip
                ? List.of(new WorldDefinition.ShipDef(1, "船", 1, Map.of()))
                : List.of();
        List<WorldDefinition.AgentDef> agentDefs = new ArrayList<>();
        for (int i = 1; i <= workers; i++) {
            agentDefs.add(new WorldDefinition.AgentDef(
                    i, "工人" + i, "RESIDENT", 1, "DILIGENT", 3));
        }
        WorldDefinition def = new WorldDefinition("t", 0, locs, List.of(), ships,
                agentDefs, List.of(), new WorldDefinition.EconomyDef(
                        List.of(ORE, METAL), recipes, new BigDecimal("500"),
                        new BigDecimal("5000"), new BigDecimal("0.001")),
                WorldDefinition.DEFAULT_SEED);
        var assembled = WorldAssembler.assemble(def);
        WorldState st = new WorldState(assembled.graph(), assembled.agents(), assembled.ledger(),
                assembled.money(), new TestChronicle(), assembled.epoch(), assembled.parents());
        assembled.factions().values().forEach(st::addFaction);
        assembled.colonies().values().forEach(st::addColony);
        return new Fixture(assembled, st, Economy.from(def.economy()));
    }

    private static RecipeDef smelt() {
        return new RecipeDef("smelt", "冶炼", 2, 1,
                Map.of("ore", new BigDecimal("2")), Map.of("metal", BigDecimal.ONE));
    }

    private static TickContext ctx() {
        return new TickContext(Map.of(), RngStreams.of(1));
    }

    // ------------------------------------------------------------ 定价

    @FcTest
    public void priceRisesWhenStockIsLow() {
        BigDecimal low = PriceModel.basePrice(ORE, new BigDecimal("20"));
        BigDecimal high = PriceModel.basePrice(ORE, new BigDecimal("200"));
        assert low.compareTo(high) > 0 : "库存低应更贵：" + low + " vs " + high;
    }

    @FcTest
    public void buyPriceAboveSellPrice() {
        BigDecimal stock = new BigDecimal("100");
        BigDecimal buy = PriceModel.buyPrice(ORE, stock);
        BigDecimal sell = PriceModel.sellPrice(ORE, stock);
        assert buy.compareTo(sell) > 0 : "买价应高于卖价（价差）：" + buy + " vs " + sell;
    }

    @FcTest
    public void priceIsClampedAtExtremes() {
        BigDecimal zero = PriceModel.basePrice(ORE, BigDecimal.ZERO);
        BigDecimal huge = PriceModel.basePrice(ORE, new BigDecimal("100000"));
        assert zero.compareTo(ORE.basePrice().multiply(new BigDecimal("5"))) <= 0 : "上限应夹紧";
        assert huge.compareTo(ORE.basePrice().multiply(new BigDecimal("0.3"))) >= 0 : "下限应夹紧";
    }

    @FcTest
    public void priceShockMultipliesPrice() {
        BigDecimal normal = PriceModel.basePrice(ORE, new BigDecimal("100"));
        BigDecimal shocked = PriceModel.basePrice(ORE, new BigDecimal("100"), new BigDecimal("2"));
        assert shocked.compareTo(normal.multiply(new BigDecimal("2"))) == 0
                : shocked + " 应为 " + normal + " 的两倍";
    }

    // ------------------------------------------------------------ 交易

    @FcTest
    public void tradeKeepsGoodsAndCreditsTotals() {
        Fixture f = world("50", "1000", List.of(), false, true);
        WorldState st = f.state;
        Economy eco = f.economy;
        TradeService trade = new TradeService(eco);
        Location.Ship ship = st.graph().ships().get(0);

        BigDecimal goodsBefore = st.ledger().total(ResourceKind.ORE);
        BigDecimal moneyBefore = st.money().total();
        BigDecimal creditsBefore = st.credits();

        var r = trade.buy(st, ship, ResourceKind.ORE, 10);
        assert r.ok() : r.message();

        assert st.ledger().total(ResourceKind.ORE).compareTo(goodsBefore) == 0 : "货物总量应守恒";
        assert st.money().total().compareTo(moneyBefore) == 0 : "信用点总量应守恒";
        assert st.credits().compareTo(creditsBefore) < 0 : "买入应减少玩家信用点";
        assert ship.cargo().amount(ResourceKind.ORE).compareTo(BigDecimal.TEN) == 0;
        assert st.money().balance(MoneyLedger.locationAccount(new Ids.LocationId(1)))
                .compareTo(new BigDecimal("1000")) > 0;
        st.verifyLedger();
    }

    @FcTest
    public void sellReducesCargoAndAddsCredits() {
        Fixture f = world("50", "1000", List.of(), false, true);
        WorldState st = f.state;
        Economy eco = f.economy;
        TradeService trade = new TradeService(eco);
        Location.Ship ship = st.graph().ships().get(0);
        st.transferStock(new Ids.LocationId(1), ship.id(), ResourceKind.ORE,
                BigDecimal.TEN, "测试装货");

        BigDecimal before = st.credits();
        BigDecimal moneyBefore = st.money().total();
        var r = trade.sell(st, ship, ResourceKind.ORE, 5);
        assert r.ok() : r.message();
        assert st.credits().compareTo(before) > 0 : "卖出应增加信用点";
        assert st.money().total().compareTo(moneyBefore) == 0 : "信用点总量应守恒";
        assert ship.cargo().amount(ResourceKind.ORE).compareTo(new BigDecimal("5")) == 0
                : "货舱应减少";
    }

    @FcTest
    public void buyIsPartiallyFilledWhenStockShort() {
        Fixture f = world("3", "1000", List.of(), false, true);
        WorldState st = f.state;
        TradeService trade = new TradeService(f.economy);
        Location.Ship ship = st.graph().ships().get(0);

        var r = trade.buy(st, ship, ResourceKind.ORE, 10);
        assert r.ok() : "库存不足应部分成交而非失败：" + r.message();
        assert ship.cargo().amount(ResourceKind.ORE).compareTo(new BigDecimal("3")) == 0
                : "应成交全部库存 3，实际 " + ship.cargo().amount(ResourceKind.ORE);
    }

    @FcTest
    public void buyRejectedWhenPlayerCannotAfford() {
        Fixture f = world("50", "1000", List.of(), false, true);
        WorldState st = f.state;
        TradeService trade = new TradeService(f.economy);
        Location.Ship ship = st.graph().ships().get(0);
        // 把玩家信用点转走，只留 1
        st.payFromPlayer(MoneyLedger.BANK, st.credits().subtract(BigDecimal.ONE), "测试");

        var r = trade.buy(st, ship, ResourceKind.ORE, 10);
        assert !r.ok() : "买不起不应成交";
        assert ship.cargo().amount(ResourceKind.ORE).signum() == 0;
    }

    // ------------------------------------------------------------ 生产

    @FcTest
    public void productionStartsAndConsumesInputs() {
        Fixture f = world("10", "1000", List.of(smelt()), true, false);
        WorldState st = f.state;
        Economy eco = f.economy;
        new ProductionSystemImpl(eco).startUnits(st, ctx());

        assert st.workUnits().size() == 1 : "应开工一个工作单元";
        assert st.goods(new Ids.LocationId(1), ResourceKind.ORE)
                .compareTo(new BigDecimal("8")) == 0 : "应扣除 2 ore";
    }

    @FcTest
    public void productionBlockedWithoutInputs() {
        Fixture f = world("1", "1000", List.of(smelt()), true, false);
        WorldState st = f.state;
        Economy eco = f.economy;
        new ProductionSystemImpl(eco).startUnits(st, ctx());
        assert st.workUnits().isEmpty() : "输入不足不应开工（缺料停产）";
        assert st.goods(new Ids.LocationId(1), ResourceKind.ORE)
                .compareTo(BigDecimal.ONE) == 0 : "未开工不得扣料";
    }

    @FcTest
    public void productionBlockedWithoutWorkers() {
        Fixture f = world("10", "1000", List.of(smelt()), false, false);
        WorldState st = f.state;
        Economy eco = f.economy;
        new ProductionSystemImpl(eco).startUnits(st, ctx());
        assert st.workUnits().isEmpty() : "没有工人不能开工";
        assert st.goods(new Ids.LocationId(1), ResourceKind.ORE)
                .compareTo(new BigDecimal("10")) == 0 : "未开工不得扣料";
    }

    @FcTest
    public void productionBlockedWhenDisrupted() {
        Fixture f = world("10", "1000", List.of(smelt()), true, false);
        WorldState st = f.state;
        st.disruptProduction(new Ids.LocationId(1), 2);
        Economy eco = f.economy;
        new ProductionSystemImpl(eco).startUnits(st, ctx());
        assert st.workUnits().isEmpty() : "停产期间不得开工";
    }

    @FcTest
    public void eachWorkerIsAssignedToAtMostOneRecipe() {
        RecipeDef second = new RecipeDef("aaa-distill", "蒸馏", 2, 1,
                Map.of("water", new BigDecimal("2")), Map.of("fuel", BigDecimal.ONE));
        Fixture f = world("10", "1000", List.of(smelt(), second), true, false);
        WorldState st = f.state;
        Economy eco = f.economy;
        new ProductionSystemImpl(eco).startUnits(st, ctx());

        assert st.workUnits().size() == 1 : "1 名工人只能投入 1 个配方，实际 "
                + st.workUnits().keySet();
        // 按配方 id 排序，"aaa-distill" 先开工
        assert st.workUnits().containsKey("aaa-distill@1");
        assert st.goods(new Ids.LocationId(1), ResourceKind.ORE)
                .compareTo(new BigDecimal("10")) == 0 : "未分配的配方不得扣料";
    }

    @FcTest
    public void existingWorkUnitReservesItsWorkers() {
        Fixture f = world("10", "1000", List.of(smelt()), true, false);
        WorldState st = f.state;
        Ids.LocationId at = new Ids.LocationId(1);
        st.putWorkUnit("in-progress", new WorkUnit("smelt", 0, 5,
                List.of(new Ids.AgentId(1)), at));

        new ProductionSystemImpl(f.economy).startUnits(st, ctx());

        assert st.workUnits().size() == 1 : "在制单元的工人不能被新配方重复占用";
        assert st.goods(at, ResourceKind.ORE).compareTo(new BigDecimal("10")) == 0
                : "未开工不得扣料";
    }

    @FcTest
    public void twoWorkersCanStartTwoRecipes() {
        RecipeDef second = new RecipeDef("aaa-distill", "蒸馏", 2, 1,
                Map.of("water", new BigDecimal("2")), Map.of("fuel", BigDecimal.ONE));
        Fixture f = world("10", "1000", List.of(smelt(), second), 2, false);
        WorldState st = f.state;

        new ProductionSystemImpl(f.economy).startUnits(st, ctx());

        assert st.workUnits().size() == 2 : "两名工人应能让两个配方同时开工，实际 "
                + st.workUnits().keySet();
        assert st.workUnits().containsKey("aaa-distill@1");
        assert st.workUnits().containsKey("smelt@1");
        assert st.goods(new Ids.LocationId(1), ResourceKind.ORE)
                .compareTo(new BigDecimal("8")) == 0 : "smelt 应扣 2 ore";
        assert st.goods(new Ids.LocationId(1), ResourceKind.WATER)
                .compareTo(new BigDecimal("18")) == 0 : "aaa-distill 应扣 2 water";
    }

    @FcTest
    public void productionCompletionAddsOutputsAndEvent() {
        Fixture f = world("10", "1000", List.of(smelt()), true, false);
        WorldState st = f.state;
        Economy eco = f.economy;
        ProductionSystemImpl prod = new ProductionSystemImpl(eco);
        prod.startUnits(st, ctx());

        TickContext c = ctx();
        WorkStage work = new WorkStage(prod);
        work.apply(st, c);
        work.apply(st, c);

        assert st.goods(new Ids.LocationId(1), ResourceKind.METAL)
                .compareTo(BigDecimal.ONE) == 0 : "应产出 1 metal";
        assert st.workUnits().isEmpty() : "完工后工作单元应移除";
        assert c.events().peek().stream()
                .anyMatch(e -> e instanceof GameEvent.ProductionCompleted) : "应记录生产完成事件";
        st.verifyLedger();
    }

    // ------------------------------------------------------------ 工资与利息

    @FcTest
    public void wagesPaidFromLocationAccountToHouseholds() {
        Fixture f = world("10", "100", List.of(), true, false);
        WorldState st = f.state;
        BigDecimal totalBefore = st.money().total();
        new MarketSystemImpl().onTick(st, ctx());

        assert st.money().balance(MoneyLedger.locationAccount(new Ids.LocationId(1)))
                .compareTo(new BigDecimal("99")) == 0 : "地点账户应支付 1 信用点工资";
        assert st.money().balance(MoneyLedger.HOUSEHOLDS)
                .compareTo(BigDecimal.ONE) == 0;
        assert st.money().total().compareTo(totalBefore) == 0 : "工资只是账户间转移";
    }

    @FcTest
    public void unpaidWagesLowerMorale() {
        Fixture f = world("10", "0", List.of(), true, false);
        WorldState st = f.state;
        Agent a = st.agents().values().iterator().next();
        int before = a.needs().morale();
        new MarketSystemImpl().onTick(st, ctx());
        assert a.needs().morale() < before : "欠薪应降低士气";
    }

    @FcTest
    public void loanInterestIsPaidToBank() {
        Fixture f = world("10", "100", List.of(), false, false);
        WorldState st = f.state;
        BigDecimal total = st.money().total();
        st.money().borrow(st.now(), MoneyLedger.PLAYER, new BigDecimal("100"), "测试借款");
        BigDecimal beforeInterest = st.money().balance(MoneyLedger.PLAYER);

        TickContext c = new TickContext(Map.of(), RngStreams.of(1), new BigDecimal("0.10"));
        new ConsumptionStage().apply(st, c);

        assert st.money().balance(MoneyLedger.PLAYER)
                .compareTo(beforeInterest.subtract(BigDecimal.TEN)) == 0
                : "应按 10% 支付 10 信用点利息";
        assert st.money().debt(MoneyLedger.PLAYER).compareTo(new BigDecimal("100")) == 0;
        assert st.money().total().compareTo(total) == 0 : "利息只是账户间转移";
    }

    @FcTest
    public void economyPricesReflectShock() {
        Fixture f = world("50", "1000", List.of(), false, true);
        WorldState st = f.state;
        Economy eco = f.economy;
        BigDecimal normal = eco.buyPrice(st, new Ids.LocationId(1), ResourceKind.ORE).orElseThrow();
        st.applyPriceShock(ResourceKind.ORE, new BigDecimal("2"), 3);
        BigDecimal shocked = eco.buyPrice(st, new Ids.LocationId(1), ResourceKind.ORE).orElseThrow();
        assert shocked.compareTo(normal.multiply(new BigDecimal("2"))) == 0
                : shocked + " 应为 " + normal + " 的两倍";
    }
}
