package fc.sim;

import fc.config.world.WorldDefinition;
import fc.sim.event.EventBus;
import fc.sim.event.EventQueue;
import fc.sim.rng.RngStreams;
import fc.sim.tick.DecideStage;
import fc.sim.tick.PerceiveStage;
import fc.sim.tick.TickContext;
import fc.sim.tick.TickPipeline;
import fc.sim.tick.TimeStage;
import fc.sim.tick.WorkStage;
import fc.sim.tick.WorldEventStage;
import fc.testkit.FcTest;
import fc.testkit.TestRunner;
import fc.world.Agent;
import fc.world.Assignment;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Needs;
import fc.world.PendingDecision;
import fc.world.ResourceKind;
import fc.world.Trait;
import fc.world.WorldState;
import fc.world.WorkUnit;
import fc.world.time.Stardate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * M2 测试：确定性随机流、事件队列、主体决策（进食/按日迁徙）、
 * 工作单元推进、待决决策暂停、状态哈希。
 */
public final class M2SimTest {

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(M2SimTest.class))) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 工具

    private static final class TestChronicle implements fc.world.Chronicle {
        private final List<GameEvent> events = new ArrayList<>();

        @Override
        public void record(GameEvent e) {
            events.add(e);
        }

        @Override
        public List<GameEvent> since(Stardate s) {
            return events.stream().filter(e -> !e.when().isBefore(s)).toList();
        }

        @Override
        public List<GameEvent> byActor(Ids.AgentId a) {
            return events.stream().filter(e -> e.who().contains(a)).toList();
        }

        @Override
        public List<GameEvent> at(Ids.LocationId l) {
            return events.stream().filter(e -> e.where().equals(l)).toList();
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

    private static WorldState stateOf(WorldAssembler.AssembledWorld w,
                                      Map<Ids.AgentId, Agent> agents) {
        WorldState st = new WorldState(w.graph(), agents, w.ledger(), w.money(),
                new TestChronicle(), w.epoch(), w.parents());
        w.factions().values().forEach(st::addFaction);
        w.colonies().values().forEach(st::addColony);
        return st;
    }

    /** 1(粮) - 2(无粮) 两地点，一个主体在 2。 */
    private static WorldState worldWith(Agent agent, String foodAt1) {
        List<WorldDefinition.LocationDef> locs = List.of(
                new WorldDefinition.LocationDef(1, "StarSystem", "A", null,
                        Map.of("food", new BigDecimal(foodAt1)), null),
                new WorldDefinition.LocationDef(2, "StarSystem", "B", null, Map.of(), null));
        List<WorldDefinition.LinkDef> links = List.of(
                new WorldDefinition.LinkDef(1, 2, "WarpRoute", 1, 0.0, BigDecimal.ONE));
        var assembled = WorldAssembler.assemble(new WorldDefinition(
                "t", 0, locs, links, List.of(), List.of()));
        Map<Ids.AgentId, Agent> agents = new LinkedHashMap<>();
        agents.put(agent.id(), agent);
        return stateOf(assembled, agents);
    }

    private static Agent hungryAgentAt(Ids.LocationId loc, int hunger) {
        return new Agent(new Ids.AgentId(1), Agent.AgentRole.CREW, Trait.STOIC, "测试",
                loc, new Needs(hunger, 10, 50), Assignment.WORKING, 3);
    }

    private static TickContext ctx(long seed) {
        return new TickContext(Map.of(), RngStreams.of(seed));
    }

    // ---------------------------------------------------------------- 随机

    @FcTest
    public void rngSameSeedSameSequence() {
        RandomGenerator a = RngStreams.of(777).stream(RngStreams.AGENTS);
        RandomGenerator b = RngStreams.of(777).stream(RngStreams.AGENTS);
        for (int i = 0; i < 20; i++) {
            assert a.nextInt() == b.nextInt() : "同种子应产生同序列（第 " + i + " 位）";
        }
    }

    @FcTest
    public void rngSubsystemsAreIndependent() {
        RngStreams s1 = RngStreams.of(777);
        RandomGenerator agents1 = s1.stream(RngStreams.AGENTS);
        int firstAgents = agents1.nextInt();
        // 同一实例上再取事件流，不应影响 agents 流的后续值
        s1.stream(RngStreams.EVENTS).nextInt();
        int secondAgents = agents1.nextInt();

        RandomGenerator fresh = RngStreams.of(777).stream(RngStreams.AGENTS);
        fresh.nextInt();
        int expectedSecond = fresh.nextInt();

        assert secondAgents == expectedSecond : "子系统流应互不干扰";
        assert firstAgents >= Integer.MIN_VALUE;
    }

    @FcTest
    public void rngDifferentSeedsDiffer() {
        int a = RngStreams.of(1).stream(RngStreams.WEATHER).nextInt();
        int b = RngStreams.of(2).stream(RngStreams.WEATHER).nextInt();
        assert a != b : "不同种子应产生不同抽样";
    }

    // ---------------------------------------------------------------- 事件队列

    @FcTest
    public void eventQueuePreservesOrder() {
        EventQueue q = new EventQueue();
        q.enqueue(new GameEvent.IonStorm(new Stardate(1), new Ids.LocationId(1), 1));
        q.enqueue(new GameEvent.IonStorm(new Stardate(2), new Ids.LocationId(1), 2));
        q.enqueue(new GameEvent.IonStorm(new Stardate(3), new Ids.LocationId(1), 3));
        var drained = q.drain();
        assert drained.size() == 3;
        assert ((GameEvent.IonStorm) drained.get(0)).severity() == 1;
        assert ((GameEvent.IonStorm) drained.get(2)).severity() == 3;
        assert q.size() == 0 : "drain 后应清空";
    }

    @FcTest
    public void stormRecordsCauseBeforeImpactsAndRaisesPrices() {
        WorldState st = worldWith(hungryAgentAt(new Ids.LocationId(1), 10), "10");
        TickContext c = ctx(1);
        c.setWeather(100);
        WorldEventStage stage = new WorldEventStage();
        List<GameEvent> events = new ArrayList<>();
        boolean found = false;
        for (int i = 0; i < 2000 && !found; i++) {
            st.advanceTo(st.now().plusDays(1));
            stage.apply(st, c);
            var drained = c.events().drain();
            events.addAll(drained);
            found = drained.stream().anyMatch(e -> e instanceof GameEvent.IonStorm);
        }
        assert found : "高天气下应能在有限 tick 内触发风暴";

        int firstStorm = -1;
        int firstImpact = -1;
        int shocks = 0;
        for (int i = 0; i < events.size(); i++) {
            if (firstStorm < 0 && events.get(i) instanceof GameEvent.IonStorm) {
                firstStorm = i;
            }
            if (firstImpact < 0 && events.get(i) instanceof GameEvent.StormImpact) {
                firstImpact = i;
            }
            if (events.get(i) instanceof GameEvent.MarketPriceShocked) {
                shocks++;
            }
        }
        assert firstStorm >= 0 : "应记录风暴本身";
        assert firstImpact < 0 || firstStorm < firstImpact
                : "风暴原因事件必须先于影响事件，实际 cause=" + firstStorm
                + " impact=" + firstImpact;
        assert shocks >= 3 : "燃料/能源/材料都应产生价格冲击事件，实际 " + shocks;
        assert st.priceShock(ResourceKind.FUEL).compareTo(BigDecimal.ONE) > 0
                : "风暴应抬高燃料价格";
    }

    // ---------------------------------------------------------------- 决策

    @FcTest
    public void starvingAgentEatsWhenFoodAvailable() {
        WorldState st = worldWith(hungryAgentAt(new Ids.LocationId(1), 90), "10");
        Agent a = st.agents().values().iterator().next();
        TickContext c = ctx(1);
        new DecideStage().apply(st, c);

        assert a.needs().hunger() < 90 : "进食后饥饿应下降，实际 " + a.needs().hunger();
        assert st.goods(new Ids.LocationId(1), ResourceKind.FOOD).compareTo(new BigDecimal("9")) == 0
                : "应消耗 1 单位口粮";
        assert c.events().peek().stream().anyMatch(e -> e instanceof GameEvent.CrewAte)
                : "应记录进食事件";
    }

    @FcTest
    public void starvingAgentBeginsTravelWithoutTeleporting() {
        // 主体在 2（无粮），1 有粮；决定迁徙后当天仍在 2
        WorldState st = worldWith(hungryAgentAt(new Ids.LocationId(2), 90), "10");
        Agent a = st.agents().values().iterator().next();
        TickContext c = ctx(1);
        new DecideStage().apply(st, c);

        assert a.location().id() == 2 : "当天不应瞬移，实际 " + a.location().id();
        assert a.isTraveling() : "应进入在途状态";
        assert a.travelDestination().id() == 1;
        assert a.assignment() == Assignment.TRAVELING;
        assert c.events().peek().stream().anyMatch(e -> e instanceof GameEvent.CrewMigrated)
                : "应记录迁徙事件";
    }

    @FcTest
    public void travelTakesLinkTicksAndArrives() {
        WorldState st = worldWith(hungryAgentAt(new Ids.LocationId(2), 90), "10");
        Agent a = st.agents().values().iterator().next();
        new DecideStage().apply(st, ctx(1));

        TickContext day1 = ctx(1);
        new TimeStage().apply(st, day1);
        new PerceiveStage().apply(st, day1);

        assert a.location().id() == 1 : "1 天航程后应抵达，实际 " + a.location().id();
        assert !a.isTraveling();
        assert day1.events().peek().stream().anyMatch(e -> e instanceof GameEvent.CrewArrived)
                : "应记录抵达事件";
    }

    @FcTest
    public void starvingAlertIsNotRepeatedEveryTick() {
        // 两地点都无粮 → 无法迁徙，应只告警一次
        List<WorldDefinition.LocationDef> locs = List.of(
                new WorldDefinition.LocationDef(9, "StarSystem", "S", null, Map.of(), null));
        var assembled = WorldAssembler.assemble(new WorldDefinition(
                "t", 0, locs, List.of(), List.of(), List.of()));
        Map<Ids.AgentId, Agent> agents = new LinkedHashMap<>();
        Agent a = hungryAgentAt(new Ids.LocationId(9), 100);
        agents.put(a.id(), a);
        WorldState st = stateOf(assembled, agents);

        TickContext c1 = ctx(1);
        new DecideStage().apply(st, c1);
        assert c1.events().peek().size() == 1 : "首次应告警一次";

        TickContext c2 = ctx(1);
        new DecideStage().apply(st, c2);
        assert c2.events().peek().isEmpty() : "同一状态下不应重复告警";
    }

    // ---------------------------------------------------------------- 工作单元

    @FcTest
    public void workStageAdvancesByWorkerCountInKeyOrder() {
        List<WorldDefinition.LocationDef> locs = List.of(
                new WorldDefinition.LocationDef(1, "StarSystem", "A", null, Map.of(), null));
        var assembled = WorldAssembler.assemble(new WorldDefinition(
                "t", 0, locs, List.of(), List.of(), List.of()));
        WorldState st = stateOf(assembled, Map.of());

        st.putWorkUnit("b", new WorkUnit("r", 0, 10,
                List.of(new Ids.AgentId(2), new Ids.AgentId(3))));
        st.putWorkUnit("a", new WorkUnit("r", 0, 10, List.of(new Ids.AgentId(1))));

        new WorkStage().apply(st, ctx(1));

        assert st.workUnits().get("a").progress() == 1 : "1 名工人推进 1";
        assert st.workUnits().get("b").progress() == 2 : "2 名工人推进 2";
    }

    @FcTest
    public void workUnitWithNoWorkersDoesNotAdvance() {
        List<WorldDefinition.LocationDef> locs = List.of(
                new WorldDefinition.LocationDef(1, "StarSystem", "A", null, Map.of(), null));
        var assembled = WorldAssembler.assemble(new WorldDefinition(
                "t", 0, locs, List.of(), List.of(), List.of()));
        WorldState st = stateOf(assembled, Map.of());

        st.putWorkUnit("idle", new WorkUnit("r", 0, 10, List.of()));
        new WorkStage().apply(st, ctx(1));
        assert st.workUnits().get("idle").progress() == 0 : "没有工人不能凭空推进";
    }

    @FcTest
    public void completedWorkUnitIsRemoved() {
        List<WorldDefinition.LocationDef> locs = List.of(
                new WorldDefinition.LocationDef(1, "StarSystem", "A", null, Map.of(), null));
        var assembled = WorldAssembler.assemble(new WorldDefinition(
                "t", 0, locs, List.of(), List.of(), List.of()));
        WorldState st = stateOf(assembled, Map.of());
        st.putWorkUnit("done", new WorkUnit("r", 3, 3, List.of()));
        assert st.workUnits().isEmpty() : "已完成的工作单元不应保留";
    }

    // ---------------------------------------------------------------- 管线与广播

    @FcTest
    public void pipelineRunsTwelveStagesAndBroadcasts() {
        List<WorldDefinition.LocationDef> locs = List.of(
                new WorldDefinition.LocationDef(1, "StarSystem", "A", null, Map.of(), null));
        var assembled = WorldAssembler.assemble(new WorldDefinition(
                "t", 0, locs, List.of(), List.of(), List.of()));
        WorldState st = stateOf(assembled, Map.of());

        RngStreams rng = RngStreams.of(5);
        EventBus bus = new EventBus();
        EventBus.CountingSubscriber counter = new EventBus.CountingSubscriber();
        bus.subscribe(counter);

        TickPipeline p = TickPipeline.m3(assembled.production(), rng, bus,
                null, null, null, null, BigDecimal.ZERO);
        assert p.stageNames().size() == 12 : "应为 12 阶段";

        for (int i = 0; i < 40; i++) {
            p.tick(st);
        }
        assert st.now().day() == 40 : "40 tick 后应在第 40 天";
        assert counter.isSubscribed() : "订阅者应已订阅";
        assert !p.lastReport().isEmpty() : "应有回合报告";
        bus.close();
    }

    @FcTest
    public void sameSeedSameHistoryAndHash() {
        List<WorldDefinition.LocationDef> locs = List.of(
                new WorldDefinition.LocationDef(1, "StarSystem", "A", null,
                        Map.of("food", new BigDecimal("50"), "water", new BigDecimal("50")), null));
        List<WorldDefinition.AgentDef> agentDefs = List.of(
                new WorldDefinition.AgentDef(1, "甲", "CREW", 1, "DILIGENT", 3));
        WorldDefinition def = new WorldDefinition("t", 0, locs, List.of(), List.of(),
                agentDefs, List.of(), WorldDefinition.EconomyDef.EMPTY,
                WorldDefinition.DEFAULT_SEED);

        History first = runHistory(def);
        History second = runHistory(def);
        assert first.events.equals(second.events) : "同种子两次历史应一致";
        assert first.hash.equals(second.hash) : "同种子两次状态哈希应一致";
    }

    private record History(String hash, String events) {
    }

    private static History runHistory(WorldDefinition def) {
        var w = WorldAssembler.assemble(def);
        WorldState st = stateOf(w, w.agents());
        TickPipeline p = TickPipeline.m3(w.production(), RngStreams.of(99), null,
                null, null, null, null, BigDecimal.ZERO);
        for (int i = 0; i < 60; i++) {
            p.tick(st);
        }
        StringBuilder sb = new StringBuilder();
        for (GameEvent e : st.chronicle().all()) {
            sb.append(e.getClass().getSimpleName()).append('|').append(e.when()).append(';');
        }
        return new History(StateHasher.hash(st), sb.toString());
    }

    @FcTest
    public void decisionReputationAppliesToItsFaction() {
        WorldState st = worldWith(hungryAgentAt(new Ids.LocationId(1), 10), "10");
        Ids.FactionId faction = new Ids.FactionId(1);
        PendingDecision d = st.raiseDecision("援助", "测试", new Ids.LocationId(1), faction,
                List.of(
                        new PendingDecision.Option("aid", "援助", "提升声望",
                                PendingDecision.Effect.GRANT_AID,
                                Map.of("reputation", new BigDecimal("5"))),
                        new PendingDecision.Option("no", "拒绝", "不提升",
                                PendingDecision.Effect.DECLINE, Map.of())));

        var r = DecisionResolver.resolve(st, d.id(), "aid");

        assert r.ok() : r.message();
        assert st.reputation(faction) == 5 : "援助应提升关联势力声望，实际 "
                + st.reputation(faction);
    }

    @FcTest
    public void stateHashCoversWorkUnitsAndDisruptions() {
        WorldState a = worldWith(hungryAgentAt(new Ids.LocationId(1), 10), "10");
        WorldState b = worldWith(hungryAgentAt(new Ids.LocationId(1), 10), "10");
        Ids.LocationId at = new Ids.LocationId(1);
        b.putWorkUnit("job", new WorkUnit("r", 1, 4,
                List.of(new Ids.AgentId(1)), at));
        b.disruptProduction(at, 2);

        assert !StateHasher.hash(a).equals(StateHasher.hash(b))
                : "工作单元与生产中断必须进入状态哈希";
    }

    @FcTest
    public void expiredProductionDisruptionIsCleared() {
        WorldState st = worldWith(hungryAgentAt(new Ids.LocationId(1), 10), "10");
        Ids.LocationId at = new Ids.LocationId(1);
        st.disruptProduction(at, 1);
        assert st.productionDisrupted(at) : "设置后应处于停产窗口";

        st.advanceTo(st.now().plusDays(2));
        st.clearExpiredModifiers();

        assert !st.productionDisruptions().containsKey(at) : "过期停产窗口应从状态中清除";
        assert !st.productionDisrupted(at);
    }

    @FcTest
    public void pendingDecisionPausesTimeUntilResolved() {
        List<WorldDefinition.LocationDef> locs = List.of(
                new WorldDefinition.LocationDef(1, "StarSystem", "A", null, Map.of(), null));
        var w = WorldAssembler.assemble(new WorldDefinition(
                "t", 0, locs, List.of(), List.of(), List.of()));
        WorldState st = stateOf(w, Map.of());
        TickPipeline p = TickPipeline.m3(w.production(), RngStreams.of(7), null,
                null, null, null, null, BigDecimal.ZERO);
        GameLoop loop = new GameLoop(st, p);

        var d = st.raiseDecision("测试决策", "必须选择", new Ids.LocationId(1), null,
                List.of(new PendingDecision.Option("A", "接受", "接受现状",
                                PendingDecision.Effect.ACCEPT, Map.of()),
                        new PendingDecision.Option("B", "拒绝", "拒绝并承担后果",
                                PendingDecision.Effect.DECLINE,
                                Map.of("reputation", new BigDecimal("-5")))));

        assert loop.next(5).day() == 0 : "有待决决策时不得推进时间";
        assert st.hasPendingDecision();

        var r = DecisionResolver.resolve(st, d.id(), "A");
        assert r.ok() : r.message();
        assert !st.hasPendingDecision();
        assert loop.next(5).day() == 5 : "解决后应可继续推进";
    }
}
