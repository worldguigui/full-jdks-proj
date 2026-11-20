package fc.runtime;

import fc.cli.Cli;
import fc.colony.ColonySystemImpl;
import fc.config.world.WorldDefinition;
import fc.config.world.XmlWorldLoader;
import fc.diplomacy.FactionSystemImpl;
import fc.economy.Economy;
import fc.economy.MarketSystemImpl;
import fc.economy.ProductionSystemImpl;
import fc.economy.TradeService;
import fc.history.InMemoryChronicle;
import fc.sim.GameLoop;
import fc.sim.StateHasher;
import fc.sim.WorldAssembler;
import fc.sim.event.EventBus;
import fc.sim.rng.RngStreams;
import fc.sim.tick.TickPipeline;
import fc.world.Ids;
import fc.world.WorldState;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一次完整装配的运行会话：配置 → 世界 → 系统 → Tick 管线 → CLI。
 *
 * <p>把装配与 REPL 分离，使同一套启动路径可被集成测试与交互运行共用。</p>
 */
public final class RuntimeSession {

    private static final String DEFAULT_WORLD = "world/default-world.xml";

    private final WorldAssembler.AssembledWorld world;
    private final WorldState state;
    private final Economy economy;
    private final RngStreams rng;
    private final TickPipeline pipeline;
    private final GameLoop loop;
    private final Cli cli;

    private RuntimeSession(WorldAssembler.AssembledWorld world, WorldState state,
                           Economy economy, RngStreams rng, TickPipeline pipeline,
                           GameLoop loop, Cli cli) {
        this.world = world;
        this.state = state;
        this.economy = economy;
        this.rng = rng;
        this.pipeline = pipeline;
        this.loop = loop;
        this.cli = cli;
    }

    /** 按命令行参数装载世界并完成全部装配。 */
    public static RuntimeSession create(String[] args) {
        WorldDefinition def = loadWorld(args);
        WorldAssembler.AssembledWorld world = WorldAssembler.assemble(def);

        WorldState state = new WorldState(
                world.graph(),
                world.agents(),
                world.ledger(),
                world.money(),
                new InMemoryChronicle(),
                world.epoch(),
                world.parents());
        world.factions().values().forEach(state::addFaction);
        world.colonies().values().forEach(state::addColony);
        for (Map.Entry<String, Integer> e : world.initialRelations().entrySet()) {
            String[] pair = e.getKey().split(":");
            state.relations().set(new Ids.FactionId(Long.parseLong(pair[0])),
                    new Ids.FactionId(Long.parseLong(pair[1])), e.getValue());
        }

        Economy economy = Economy.from(def.economy());
        ProductionSystemImpl productionSystem = new ProductionSystemImpl(economy);
        MarketSystemImpl marketSystem = new MarketSystemImpl();
        FactionSystemImpl factionSystem = new FactionSystemImpl();
        ColonySystemImpl colonySystem = new ColonySystemImpl();
        TradeService trade = new TradeService(economy);

        RngStreams rng = RngStreams.of(world.seed());
        EventBus bus = new EventBus();
        bus.subscribe(new EventBus.CountingSubscriber());

        TickPipeline pipeline = TickPipeline.m3(world.production(), rng, bus,
                productionSystem, marketSystem, factionSystem, colonySystem,
                world.loanDailyRate());
        GameLoop loop = new GameLoop(state, pipeline);
        Cli cli = new Cli(loop, trade);
        return new RuntimeSession(world, state, economy, rng, pipeline, loop, cli);
    }

    /** 启动横幅（每行一条）。 */
    public List<String> banner() {
        List<String> lines = new ArrayList<>();
        lines.add("Frontier Chronicle —— 边境纪事");
        lines.add("世界：" + world.name() + "，地点 " + world.graph().placeCount()
                + " 个，航线 " + world.graph().links().size()
                + " 条，舰船 " + world.graph().ships().size() + " 艘");
        lines.add("主体 " + world.agents().size() + " 人｜势力 " + world.factions().size()
                + " 个｜殖民地 " + world.colonies().size()
                + " 处｜随机种子 " + world.seed() + "（" + rng.algorithm() + "）");
        lines.add("经济：资源 " + economy.resources().size() + " 种｜配方 "
                + economy.recipes().size() + " 条｜初始信用点 "
                + state.credits().stripTrailingZeros().toPlainString()
                + "｜贷款利率 " + world.loanDailyRate().stripTrailingZeros().toPlainString() + "/日");
        lines.add("Tick 阶段（12）：" + String.join(" → ", pipeline.stageNames()));
        lines.add("当前 " + state.now() + "（" + state.now().toIsoDate() + "）"
                + "｜状态哈希 " + StateHasher.hash(state));
        lines.add("提示：输入 map 看星图，help 看全部命令，quit 退出。");
        return lines;
    }

    public WorldState state() {
        return state;
    }

    public Cli cli() {
        return cli;
    }

    private static WorldDefinition loadWorld(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--world".equals(args[i])) {
                if (i + 1 >= args.length) {
                    throw new fc.config.ConfigException("--world 需要文件路径");
                }
                return XmlWorldLoader.loadFile(Path.of(args[i + 1]));
            }
        }
        return XmlWorldLoader.loadResource(DEFAULT_WORLD);
    }
}
