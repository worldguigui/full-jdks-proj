package fc.economy;

import fc.config.world.RecipeDef;
import fc.config.world.ResourceDef;
import fc.config.world.WorldDefinition;
import fc.sim.WorldAssembler;
import fc.sim.tick.TickContext;
import fc.sim.rng.RngStreams;
import fc.testkit.FcTest;
import fc.testkit.TestRunner;
import fc.world.Agent;
import fc.world.Assignment;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Needs;
import fc.world.Stock;
import fc.world.Trait;
import fc.world.WorldState;
import fc.world.WorkUnit;
import fc.world.time.Stardate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M3 经济测试：供需定价、交易守恒、部分成交、配方开工/完工、工资与欠薪。
 */
public final class EconomyTest {

    public static void main(String[] args) {
        TestRunner.report(TestRunner.run(EconomyTest.class));
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

    /** 单地点世界：ore 库存可调，可选配方 ore→alloy，附带一个主体。 */
    private static WorldState world(String ore, String credits, boolean withRecipe,
                                    boolean agentWorking) {
        List<WorldDefinition.LocationDef> locs = List.of(
                new WorldDefinition.LocationDef(1, "Colony", "C", null,
                        Map.of("ore", new BigDecimal(ore), "credits", new BigDecimal(credits)), null));
        List<RecipeDef> recipes = withRecipe
                ? List.of(new RecipeDef("smelt", "冶炼", 2, 1,
                Map.of("ore", new BigDecimal("2")), Map.of("alloy", BigDecimal.ONE)))
                : List.of();
        var economyDef = new WorldDefinition.EconomyDef(List.of(ORE), recipes, new BigDecimal("500"));
        var def = new WorldDefinition("t", 0, locs, List.of(), List.of(), List.of(), economyDef);
        var assembled = WorldAssembler.assemble(def);

        Map<Ids.AgentId, Agent> agents = new LinkedHashMap<>();
        if (agentWorking) {
            Agent a = new Agent(new Ids.AgentId(1), Agent.AgentRole.RESIDENT, Trait.DILIGENT,
                    "工人", new Ids.LocationId(1), Needs.INITIAL,
                    Assignment.WORKING, 3);
            agents.put(a.id(), a);
        }
        WorldState st = new WorldState(assembled.graph(), agents, assembled.ledger(),
                new TestChronicle(), assembled.epoch(), assembled.parents());
        st.setCredits(new BigDecimal("500"));
        return st;
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

    // ------------------------------------------------------------ 交易

    @FcTest
    public void tradeKeepsGoodsAndCreditsTotals() {
        WorldState st = world("50", "1000", false, false);
        Economy eco = Economy.from(new WorldDefinition.EconomyDef(List.of(ORE), List.of(),
                new BigDecimal("500")));
        TradeService trade = new TradeService(eco);
        var ship = new fc.world.Location.Ship(new Ids.LocationId(999), Stock.empty(), "船",
                new Ids.ShipId(1), new Ids.LocationId(1), Stock.empty(), null);

        BigDecimal goodsBefore = st.graph().get(new Ids.LocationId(1)).stock().amount("ore");
        BigDecimal creditsBefore = st.credits()
                .add(st.graph().get(new Ids.LocationId(1)).stock().amount("credits"));

        var r = trade.buy(st, ship, "ore", 10);
        assert r.ok() : r.message();

        BigDecimal goodsAfter = st.graph().get(new Ids.LocationId(1)).stock().amount("ore")
                .add(ship.cargo().amount("ore"));
        BigDecimal creditsAfter = st.credits()
                .add(st.graph().get(new Ids.LocationId(1)).stock().amount("credits"));
        assert goodsBefore.compareTo(goodsAfter) == 0 : "货物总量应守恒";
        assert creditsBefore.compareTo(creditsAfter) == 0 : "信用点总量应守恒";
    }

    @FcTest
    public void sellReducesCargoAndAddsCredits() {
        WorldState st = world("50", "1000", false, false);
        Economy eco = Economy.from(new WorldDefinition.EconomyDef(List.of(ORE), List.of(),
                new BigDecimal("500")));
        TradeService trade = new TradeService(eco);
        var ship = new fc.world.Location.Ship(new Ids.LocationId(999), Stock.empty(), "船",
                new Ids.ShipId(1), new Ids.LocationId(1),
                Stock.of(Map.of("ore", new BigDecimal("10"))), null);

        BigDecimal before = st.credits();
        var r = trade.sell(st, ship, "ore", 5);
        assert r.ok() : r.message();
        assert st.credits().compareTo(before) > 0 : "卖出应增加信用点";
        assert ship.cargo().amount("ore").compareTo(new BigDecimal("5")) == 0 : "货舱应减少";
    }

    @FcTest
    public void buyIsPartiallyFilledWhenStockShort() {
        WorldState st = world("3", "1000", false, false);
        Economy eco = Economy.from(new WorldDefinition.EconomyDef(List.of(ORE), List.of(),
                new BigDecimal("500")));
        TradeService trade = new TradeService(eco);
        var ship = new fc.world.Location.Ship(new Ids.LocationId(999), Stock.empty(), "船",
                new Ids.ShipId(1), new Ids.LocationId(1), Stock.empty(), null);

        var r = trade.buy(st, ship, "ore", 10);
        assert r.ok() : "库存不足应部分成交而非失败：" + r.message();
        assert ship.cargo().amount("ore").compareTo(new BigDecimal("3")) == 0
                : "应成交全部库存 3，实际 " + ship.cargo().amount("ore");
    }

    // ------------------------------------------------------------ 生产

    @FcTest
    public void productionStartsAndConsumesInputs() {
        WorldState st = world("10", "1000", true, true);
        Economy eco = Economy.from(new WorldDefinition.EconomyDef(List.of(ORE),
                List.of(new RecipeDef("smelt", "冶炼", 2, 1,
                        Map.of("ore", new BigDecimal("2")), Map.of("alloy", BigDecimal.ONE))),
                new BigDecimal("500")));
        new ProductionSystemImpl(eco).startUnits(st, ctx());

        assert st.workUnits().size() == 1 : "应开工一个工作单元";
        assert st.graph().get(new Ids.LocationId(1)).stock().amount("ore")
                .compareTo(new BigDecimal("8")) == 0 : "应扣除 2 ore";
    }

    @FcTest
    public void productionBlockedWithoutInputs() {
        WorldState st = world("1", "1000", true, true);
        Economy eco = Economy.from(new WorldDefinition.EconomyDef(List.of(ORE),
                List.of(new RecipeDef("smelt", "冶炼", 2, 1,
                        Map.of("ore", new BigDecimal("2")), Map.of("alloy", BigDecimal.ONE))),
                new BigDecimal("500")));
        new ProductionSystemImpl(eco).startUnits(st, ctx());
        assert st.workUnits().isEmpty() : "输入不足不应开工（缺料停产）";
    }

    @FcTest
    public void productionCompletionAddsOutputs() {
        WorldState st = world("10", "1000", true, true);
        Economy eco = Economy.from(new WorldDefinition.EconomyDef(List.of(ORE),
                List.of(new RecipeDef("smelt", "冶炼", 2, 1,
                        Map.of("ore", new BigDecimal("2")), Map.of("alloy", BigDecimal.ONE))),
                new BigDecimal("500")));
        ProductionSystemImpl prod = new ProductionSystemImpl(eco);
        prod.startUnits(st, ctx());

        var entry = st.workUnits().entrySet().iterator().next();
        WorkUnit done = entry.getValue().advance().advance();
        TickContext ctx = ctx();
        prod.onComplete(st, ctx, entry.getKey(), done);

        assert st.graph().get(new Ids.LocationId(1)).stock().amount("alloy")
                .compareTo(BigDecimal.ONE) == 0 : "应产出 1 alloy";
        assert ctx.events().peek().stream()
                .anyMatch(e -> e instanceof GameEvent.ProductionCompleted) : "应记录生产完成事件";
    }

    // ------------------------------------------------------------ 工资

    @FcTest
    public void wagesPaidFromLocationCredits() {
        WorldState st = world("10", "100", false, true);
        new MarketSystemImpl().onTick(st, ctx());
        assert st.graph().get(new Ids.LocationId(1)).stock().amount("credits")
                .compareTo(new BigDecimal("99")) == 0 : "应支付 1 信用点工资";
    }

    @FcTest
    public void unpaidWagesLowerMorale() {
        WorldState st = world("10", "0", false, true);
        Agent a = st.agents().values().iterator().next();
        int before = a.needs().morale();
        new MarketSystemImpl().onTick(st, ctx());
        assert a.needs().morale() < before : "欠薪应降低士气";
    }
}
