package fc.runtime;

import fc.cli.Cli;
import fc.colony.ColonySystemImpl;
import fc.config.world.WorldDefinition;
import fc.config.world.XmlWorldLoader;
import fc.colony.ShipyardService;
import fc.diplomacy.ConflictResolver;
import fc.diplomacy.FactionSystemImpl;
import fc.diplomacy.GoalService;
import fc.economy.Economy;
import fc.economy.MarketSystemImpl;
import fc.economy.ProductionSystemImpl;
import fc.economy.TradeService;
import fc.history.InMemoryChronicle;
import fc.science.ResearchSystemImpl;
import fc.sim.GameLoop;
import fc.sim.StateHasher;
import fc.sim.WorldAssembler;
import fc.sim.event.EventBus;
import fc.sim.rng.RngStreams;
import fc.sim.spi.ProductionSystem;
import fc.sim.tick.TickPipeline;
import fc.world.Ids;
import fc.world.WorldState;

import fc.persist.SaveException;
import fc.persist.SavePayload;
import fc.persist.SaveRepository;

import java.io.IOException;
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
    private final String worldSource;
    private final List<String> commandHistory = new ArrayList<>();

    private RuntimeSession(WorldAssembler.AssembledWorld world, WorldState state,
                           Economy economy, RngStreams rng, TickPipeline pipeline,
                           GameLoop loop, Cli cli, String worldSource) {
        this.world = world;
        this.state = state;
        this.economy = economy;
        this.rng = rng;
        this.pipeline = pipeline;
        this.loop = loop;
        this.cli = cli;
        this.worldSource = worldSource;
    }

    /** 按命令行参数装载世界并完成全部装配。 */
    public static RuntimeSession create(String[] args) {
        WorldDefinition def = loadWorld(args);
        return assemble(def, sourceOf(args));
    }

    /** 从已装载的世界定义装配（读档时复用）。 */
    private static RuntimeSession assemble(WorldDefinition def, String worldSource) {
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
        for (Map.Entry<Ids.LocationId, Ids.FactionId> e : world.owners().entrySet()) {
            state.setLocationOwner(e.getKey(), e.getValue());
        }
        for (Map.Entry<Ids.LocationId, fc.world.KnowledgeState> e
                : world.knowledge().entrySet()) {
            state.setKnowledge(e.getKey(), e.getValue());
        }
        for (Map.Entry<Ids.LocationId, Integer> e : world.workerCapacity().entrySet()) {
            state.setWorkerCapacity(e.getKey(), e.getValue());
        }
        for (Map.Entry<Ids.LocationId, String> e : world.facilityKinds().entrySet()) {
            state.setFacilityKind(e.getKey(), e.getValue());
        }
        state.seedDetectedNeighbours();
        state.recordCargoSnapshot();
        for (Map.Entry<String, Integer> e : world.initialRelations().entrySet()) {
            String[] pair = e.getKey().split(":");
            state.relations().set(new Ids.FactionId(Long.parseLong(pair[0])),
                    new Ids.FactionId(Long.parseLong(pair[1])), e.getValue());
        }

        Economy economy = Economy.from(def.economy());
        ProductionSystemImpl productionSystem = new ProductionSystemImpl(economy);
        ResearchSystemImpl researchSystem = new ResearchSystemImpl();
        MarketSystemImpl marketSystem = new MarketSystemImpl();
        FactionSystemImpl factionSystem = new FactionSystemImpl();
        ColonySystemImpl colonySystem = new ColonySystemImpl();
        TradeService trade = new TradeService(economy);

        RngStreams rng = RngStreams.of(world.seed());
        EventBus bus = new EventBus();
        bus.subscribe(new EventBus.CountingSubscriber());

        Map<Ids.LocationId, Map<fc.world.ResourceKind, java.math.BigDecimal>> natural =
                new java.util.TreeMap<>();
        for (Map.Entry<Ids.LocationId, WorldAssembler.Production> e
                : world.production().entrySet()) {
            natural.put(e.getKey(), Map.of(e.getValue().resource(), e.getValue().perDay()));
        }
        ConflictResolver conflicts = new ConflictResolver();
        GoalService goals = new GoalService(def, natural);
        ShipyardService shipyards = new ShipyardService(world.shipTypes());

        TickPipeline pipeline = TickPipeline.m4(world.production(), rng, bus,
                List.<ProductionSystem>of(productionSystem, researchSystem),
                marketSystem, factionSystem, colonySystem,
                world.loanDailyRate(), world.events(), conflicts, goals);
        GameLoop loop = new GameLoop(state, pipeline);
        Cli cli = new Cli(loop, trade, shipyards, goals,
                () -> new fc.sim.tick.TickContext(world.production(), rng,
                        world.loanDailyRate(), world.events()));
        return new RuntimeSession(world, state, economy, rng, pipeline, loop, cli,
                worldSource);
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

    public String worldSource() {
        return worldSource;
    }

    /** 存档中使用过的命令（只读快照）。 */
    public List<String> commandHistory() {
        return List.copyOf(commandHistory);
    }

    /**
     * 执行命令并记录可重放历史。
     *
     * <p>交互入口与测试都应走本方法；{@link #cli()} 保留给只读诊断，
     * 直接调用不会进入存档。</p>
     */
    public String execute(String line) {
        if (line == null || line.isBlank()) {
            return "";
        }
        String command = line.strip();
        if (replayable(command)) {
            commandHistory.add(command);
        }
        return cli.execute(command);
    }

    /** 纯查询与退出命令不写入重放日志，避免存档随浏览历史膨胀。 */
    private static boolean replayable(String command) {
        String name = command.split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        return switch (name) {
            case "save", "load", "saves", "hash", "help", "quit", "exit",
                    "query", "export", "atlas", "chart", "music", "soundtrack",
                    "vault", "archive", "seal", "unseal", "jmx", "chat", "res", "sign",
            "verify" -> false;
            default -> true;
        };
    }

    /** 保存到槽位；返回人类可读结果。 */
    public String save(SaveRepository repository, String slot, String password)
            throws IOException {
        SavePayload payload = new SavePayload(worldSource,
                StateHasher.hash(state), state.now().day(), commandHistory);
        Path file = repository.save(slot, payload, password);
        return "已保存槽位 " + slot + " → " + file + "（" + state.now()
                + "，命令 " + commandHistory.size() + " 条，哈希 "
                + abbreviate(payload.stateHash()) + "）";
    }

    /**
     * 读档：从存档记录的世界来源重新装配，按序重放命令，最后比对状态哈希。
     *
     * @throws SaveException 口令/格式错误或重放后的哈希与存档不一致
     */
    public static RuntimeSession load(SaveRepository repository, String slot, String password)
            throws IOException {
        SavePayload payload = repository.load(slot, password);
        RuntimeSession session = assemble(loadDefinition(payload.worldSource()),
                payload.worldSource());
        for (String command : payload.commands()) {
            session.execute(command);
        }
        String actual = StateHasher.hash(session.state());
        if (!actual.equals(payload.stateHash())) {
            throw new SaveException("读档校验失败：重放后的状态与存档不一致（存档 "
                    + payload.stateHash() + "，重放 " + actual + "，当前第 "
                    + session.state().now().day() + " 天）");
        }
        return session;
    }

    private static WorldDefinition loadDefinition(String source) throws SaveException {
        try {
            if (source.startsWith("resource:")) {
                return XmlWorldLoader.loadResource(source.substring("resource:".length()));
            }
            if (source.startsWith("file:")) {
                return XmlWorldLoader.loadFile(Path.of(source.substring("file:".length())));
            }
        } catch (fc.config.ConfigException e) {
            throw new SaveException("读档时无法装载世界配置: " + source, e);
        }
        throw new SaveException("未知世界来源: " + source);
    }

    private static String sourceOf(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--world".equals(args[i])) {
                if (i + 1 >= args.length) {
                    throw new fc.config.ConfigException("--world 需要文件路径");
                }
                return "file:" + Path.of(args[i + 1]).toAbsolutePath().normalize();
            }
        }
        return "resource:" + DEFAULT_WORLD;
    }

    private static String abbreviate(String hash) {
        return hash.length() <= 12 ? hash : hash.substring(0, 12) + "…";
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
