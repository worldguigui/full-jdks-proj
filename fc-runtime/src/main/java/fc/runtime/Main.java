package fc.runtime;

import fc.cli.Cli;
import fc.economy.Economy;
import fc.economy.MarketSystemImpl;
import fc.economy.ProductionSystemImpl;
import fc.economy.TradeService;
import fc.config.Config;
import fc.config.PropertiesConfig;
import fc.config.world.WorldDefinition;
import fc.config.world.XmlWorldLoader;
import fc.history.InMemoryChronicle;
import fc.sim.GameLoop;
import fc.sim.WorldAssembler;
import fc.sim.event.EventBus;
import fc.sim.rng.RngStreams;
import fc.sim.tick.TickPipeline;
import fc.world.WorldState;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/**
 * 应用装配根与启动器（main）。
 *
 * <p>M1 起：世界数据来自 {@code world.xml}（XSD 校验），经 {@link WorldAssembler} 装配为领域对象，
 * 接入 {@link TickPipeline}（时间/移动/库存三阶段），再进入 CLI 交互循环。</p>
 */
public final class Main {

    private static final String DEFAULT_WORLD = "world/default-world.xml";

    private Main() {
    }

    public static void main(String[] args) {
        forceUtf8StdoutIfNeeded();

        try {
            Config config = loadConfig(args);
            WorldDefinition def = loadWorld(args);
            WorldAssembler.AssembledWorld world = WorldAssembler.assemble(def);

            WorldState state = new WorldState(
                    world.graph(),
                    world.agents(),
                    world.ledger(),
                    new InMemoryChronicle(),
                    world.epoch(),
                    world.parents());

            // 经济：价格模型 + 配方生产 + 工资结算
            Economy economy = Economy.from(def.economy());
            state.setCredits(def.economy().playerCredits());
            ProductionSystemImpl productionSystem = new ProductionSystemImpl(economy);
            MarketSystemImpl marketSystem = new MarketSystemImpl();
            TradeService trade = new TradeService(economy);

            RngStreams rng = RngStreams.of(world.seed());
            EventBus bus = new EventBus();
            bus.subscribe(new EventBus.CountingSubscriber());

            TickPipeline pipeline = TickPipeline.m3(world.production(), rng, bus,
                    productionSystem, marketSystem);
            GameLoop loop = new GameLoop(state, pipeline);
            Cli cli = new Cli(loop, trade);

            System.out.println("Frontier Chronicle —— 边境纪事");
            System.out.println("世界：" + world.name() + "，地点 " + world.graph().placeCount()
                    + " 个，航线 " + world.graph().links().size()
                    + " 条，舰船 " + world.graph().ships().size() + " 艘");
            System.out.println("主体 " + world.agents().size() + " 人｜随机种子 " + world.seed()
                    + "（" + rng.algorithm() + "）");
            System.out.println("经济：资源 " + economy.resources().size() + " 种｜配方 "
                    + economy.recipes().size() + " 条｜初始信用点 "
                    + state.credits().stripTrailingZeros().toPlainString());
            System.out.println("Tick 阶段（12）：" + String.join(" → ", pipeline.stageNames()));
            System.out.println("当前 " + state.now() + "（" + state.now().toIsoDate() + "）");
            System.out.println("提示：输入 map 看星图，help 看全部命令，quit 退出。");

            repl(cli);
        } catch (fc.config.ConfigException e) {
            System.err.println("启动失败：" + e.getMessage());
            System.exit(2);
        }
    }

    /**
     * 输出编码处理。
     *
     * <p>Windows 下 JVM 的 stdout 编码默认是控制台代码页（GBK/cp936），中文会乱码；
     * 而 IDEA 控制台通常已是 UTF-8。因此：</p>
     * <ul>
     *   <li>stdout 已是 UTF-8（如 IDEA 控制台）→ 不动，避免二次转码；</li>
     *   <li>否则（Git Bash / 重定向等）→ 显式切换为 UTF-8；</li>
     *   <li>可用 {@code -Dfc.stdout.utf8=false} 关闭本行为（例如在 chcp 936 的原生 cmd 里）。</li>
     * </ul>
     */
    private static void forceUtf8StdoutIfNeeded() {
        if ("false".equalsIgnoreCase(System.getProperty("fc.stdout.utf8", "true"))) {
            return;
        }
        String enc = System.getProperty("stdout.encoding",
                System.getProperty("file.encoding", "UTF-8"));
        if (enc != null && enc.toUpperCase(java.util.Locale.ROOT).contains("UTF")) {
            return;
        }
        System.setOut(new java.io.PrintStream(
                new java.io.FileOutputStream(java.io.FileDescriptor.out),
                false, StandardCharsets.UTF_8));
    }

    private static Config loadConfig(String[] args) {
        String path = "fc-default.properties";
        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i]) && i + 1 < args.length) {
                path = args[i + 1];
            }
        }
        return PropertiesConfig.load(path);
    }

    private static WorldDefinition loadWorld(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--world".equals(args[i]) && i + 1 < args.length) {
                return XmlWorldLoader.loadFile(Path.of(args[i + 1]));
            }
        }
        return XmlWorldLoader.loadResource(DEFAULT_WORLD);
    }

    private static void repl(Cli cli) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            while (true) {
                System.out.print("> ");
                System.out.flush();
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                String out = cli.execute(line);
                if ("bye".equals(out)) {
                    System.out.println("再见。");
                    break;
                }
                if (!out.isEmpty()) {
                    System.out.println(out);
                }
            }
        } catch (Exception e) {
            System.err.println("REPL 异常: " + e);
        }
    }
}
