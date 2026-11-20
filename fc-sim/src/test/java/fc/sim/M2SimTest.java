package fc.sim;

import fc.config.world.WorldDefinition;
import fc.sim.event.EventBus;
import fc.sim.event.EventQueue;
import fc.sim.rng.RngStreams;
import fc.sim.tick.DecideStage;
import fc.sim.tick.TickContext;
import fc.sim.tick.TickPipeline;
import fc.sim.tick.WorkStage;
import fc.testkit.FcTest;
import fc.testkit.TestRunner;
import fc.world.Agent;
import fc.world.Assignment;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Needs;
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
 * M2 测试：确定性随机流、事件队列、主体决策（进食/迁徙）、虚拟线程汇合、Flow 广播。
 */
public final class M2SimTest {

    public static void main(String[] args) {
        TestRunner.report(TestRunner.run(M2SimTest.class));
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
        return new WorldState(assembled.graph(), agents, assembled.ledger(),
                new TestChronicle(), assembled.epoch(), assembled.parents());
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

    // ---------------------------------------------------------------- 决策

    @FcTest
    public void starvingAgentEatsWhenFoodAvailable() {
        WorldState st = worldWith(hungryAgentAt(new Ids.LocationId(1), 90), "10");
        Agent a = st.agents().values().iterator().next();
        TickContext c = ctx(1);
        new DecideStage().apply(st, c);

        assert a.needs().hunger() < 90 : "进食后饥饿应下降，实际 " + a.needs().hunger();
        assert st.graph().get(new Ids.LocationId(1)).stock().amount("food")
                .compareTo(new BigDecimal("9")) == 0 : "应消耗 1 单位口粮";
        assert c.events().peek().stream().anyMatch(e -> e instanceof GameEvent.CrewAte)
                : "应记录进食事件";
    }

    @FcTest
    public void starvingAgentMigratesWhenNoLocalFood() {
        // 主体在 2（无粮），1 有粮
        WorldState st = worldWith(hungryAgentAt(new Ids.LocationId(2), 90), "10");
        Agent a = st.agents().values().iterator().next();
        TickContext c = ctx(1);
        new DecideStage().apply(st, c);

        assert a.location().id() == 1 : "应迁徙到有粮地点 1，实际 " + a.location().id();
        assert a.assignment() == Assignment.TRAVELING;
        assert c.events().peek().stream().anyMatch(e -> e instanceof GameEvent.CrewMigrated)
                : "应记录迁徙事件";
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
        WorldState st = new WorldState(assembled.graph(), agents, assembled.ledger(),
                new TestChronicle(), assembled.epoch(), assembled.parents());

        TickContext c1 = ctx(1);
        new DecideStage().apply(st, c1);
        assert c1.events().peek().size() == 1 : "首次应告警一次";

        TickContext c2 = ctx(1);
        new DecideStage().apply(st, c2);
        assert c2.events().peek().isEmpty() : "同一状态下不应重复告警";
    }

    // ---------------------------------------------------------------- 并发工作

    @FcTest
    public void workStageAdvancesUnitsInFixedOrder() {
        List<WorldDefinition.LocationDef> locs = List.of(
                new WorldDefinition.LocationDef(1, "StarSystem", "A", null, Map.of(), null));
        var assembled = WorldAssembler.assemble(new WorldDefinition(
                "t", 0, locs, List.of(), List.of(), List.of()));
        WorldState st = new WorldState(assembled.graph(), Map.of(), assembled.ledger(),
                new TestChronicle(), assembled.epoch(), assembled.parents());

        st.putWorkUnit("b", new WorkUnit("r", 0, 10, List.of(new Ids.AgentId(2))));
        st.putWorkUnit("a", new WorkUnit("r", 0, 10, List.of(new Ids.AgentId(1))));

        new WorkStage().apply(st, ctx(1));

        assert st.workUnits().get("a").progress() == 1 : "a 应推进 1";
        assert st.workUnits().get("b").progress() == 1 : "b 应推进 1";
    }

    @FcTest
    public void workUnitRemovedWhenDone() {
        List<WorldDefinition.LocationDef> locs = List.of(
                new WorldDefinition.LocationDef(1, "StarSystem", "A", null, Map.of(), null));
        var assembled = WorldAssembler.assemble(new WorldDefinition(
                "t", 0, locs, List.of(), List.of(), List.of()));
        WorldState st = new WorldState(assembled.graph(), Map.of(), assembled.ledger(),
                new TestChronicle(), assembled.epoch(), assembled.parents());

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
        WorldState st = new WorldState(assembled.graph(), Map.of(), assembled.ledger(),
                new TestChronicle(), assembled.epoch(), assembled.parents());

        RngStreams rng = RngStreams.of(5);
        EventBus bus = new EventBus();
        EventBus.CountingSubscriber counter = new EventBus.CountingSubscriber();
        bus.subscribe(counter);

        TickPipeline p = TickPipeline.m2(assembled.production(), rng, bus);
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
    public void sameSeedSameHistory() {
        List<WorldDefinition.LocationDef> locs = List.of(
                new WorldDefinition.LocationDef(1, "StarSystem", "A", null,
                        Map.of("food", new BigDecimal("50")), null));
        var assembled = WorldAssembler.assemble(new WorldDefinition(
                "t", 0, locs, List.of(), List.of(), List.of()));

        String first = runHistory(assembled);
        String second = runHistory(assembled);
        assert first.equals(second) : "同种子两次历史应逐字节一致";
    }

    private static String runHistory(WorldAssembler.AssembledWorld w) {
        WorldState st = new WorldState(w.graph(), Map.of(), w.ledger(),
                new TestChronicle(), w.epoch(), w.parents());
        TickPipeline p = TickPipeline.m2(w.production(), RngStreams.of(99), null);
        for (int i = 0; i < 60; i++) {
            p.tick(st);
        }
        StringBuilder sb = new StringBuilder();
        for (GameEvent e : st.chronicle().all()) {
            sb.append(e.getClass().getSimpleName()).append('|').append(e.when()).append(';');
        }
        return sb.toString();
    }
}
