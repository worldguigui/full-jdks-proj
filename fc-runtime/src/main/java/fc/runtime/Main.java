package fc.runtime;

import fc.extensions.FrontierCatalog;
import fc.extensions.GameTelemetry;
import fc.extensions.ModLoader;
import fc.extensions.StarMapRenderer;
import fc.extensions.StatusServer;
import fc.persist.SaveRepository;
import fc.world.WorldState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 应用启动器：建立运行会话，打印启动横幅并进入 CLI 交互循环。
 *
 * <p>世界数据全部来自 {@code world.xml}（XSD 校验），经 {@link RuntimeSession}
 * 装配为领域对象并注入系统实现。</p>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        forceUtf8StdoutIfNeeded();

        List<ModLoader.LoadedMod> mods = new ArrayList<>();
        AtomicReference<RuntimeSession> session = new AtomicReference<>();
        GameTelemetry telemetry = null;
        FrontierCatalog catalog = null;
        StatusServer status = null;
        try {
            session.set(RuntimeSession.create(args));
            session.get().banner().forEach(System.out::println);

            loadMods(mods);
            telemetry = registerTelemetry();
            catalog = registerCatalog();
            status = startStatusServer(args, session);

            repl(session, telemetry);
        } catch (fc.config.ConfigException e) {
            System.err.println("启动失败：" + e.getMessage());
            System.exit(2);
        } catch (RuntimeException e) {
            System.err.println("启动失败：" + e.getMessage());
            System.exit(2);
        } finally {
            if (status != null) {
                status.close();
            }
            if (catalog != null) {
                catalog.close();
            }
            if (telemetry != null) {
                telemetry.close();
            }
            for (ModLoader.LoadedMod mod : mods) {
                mod.close();
            }
        }
    }

    /** 加载工作目录 {@code mods/} 下的扩展；单个失败只警告。 */
    private static void loadMods(List<ModLoader.LoadedMod> into) {
        Path directory = Path.of("mods");
        if (!java.nio.file.Files.isDirectory(directory)) {
            return;
        }
        ModLoader.LoadReport report = new ModLoader(directory, false).loadAll();
        for (ModLoader.LoadedMod mod : report.mods()) {
            System.out.println("已加载 mod：" + mod.descriptor().id() + " "
                    + mod.descriptor().version() + "（" + mod.descriptor().name() + "）"
                    + (mod.signers().isEmpty() ? "" : " 已签名"));
            into.add(mod);
        }
        for (String error : report.errors()) {
            System.err.println("mod 加载失败：" + error);
        }
    }

    /** 注册 JMX 遥测；不可用时降级为纯单机运行。 */
    private static GameTelemetry registerTelemetry() {
        try {
            GameTelemetry telemetry = GameTelemetry.register();
            System.out.println("JMX 遥测已注册：" + GameTelemetry.OBJECT_NAME);
            return telemetry;
        } catch (javax.management.JMException | RuntimeException e) {
            System.err.println("JMX 遥测不可用（已降级）：" + e.getMessage());
            return null;
        }
    }

    /** 注册动态 MBean 遥测目录；不可用时降级。 */
    private static FrontierCatalog registerCatalog() {
        try {
            FrontierCatalog catalog = FrontierCatalog.register();
            System.out.println("JMX 遥测目录：" + FrontierCatalog.OBJECT_NAME);
            return catalog;
        } catch (javax.management.JMException | RuntimeException e) {
            System.err.println("JMX 遥测目录不可用（已降级）：" + e.getMessage());
            return null;
        }
    }

    /** {@code --serve[=port]}：在回环地址启动只读状态端点。 */
    private static StatusServer startStatusServer(String[] args,
                                                  AtomicReference<RuntimeSession> session) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            int port = 0;
            boolean requested = false;
            if ("--serve".equals(arg)) {
                requested = true;
                if (i + 1 < args.length && args[i + 1].matches("\\d+")) {
                    port = Integer.parseInt(args[i + 1]);
                }
            } else if (arg.startsWith("--serve=")) {
                requested = true;
                try {
                    port = Integer.parseInt(arg.substring("--serve=".length()));
                } catch (NumberFormatException e) {
                    System.err.println("--serve 端口非法，改用自动分配");
                }
            }
            if (requested) {
                try {
                    StatusServer server = StatusServer.start(port,
                            () -> jsonOf(session.get()));
                    System.out.println("状态端点：" + server.url());
                    return server;
                } catch (IOException e) {
                    System.err.println("状态端点启动失败（已降级）：" + e.getMessage());
                    return null;
                }
            }
        }
        return null;
    }

    private static String jsonOf(RuntimeSession session) {
        WorldState state = session.state();
        return StatusServer.json(state.now().day(), state.now().toString(),
                fc.sim.StateHasher.hash(state), state.graph().placeCount(),
                state.graph().ships().size(),
                state.credits().stripTrailingZeros().toPlainString(),
                state.ending() == null ? "" : state.ending());
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

    private static void repl(AtomicReference<RuntimeSession> holder, GameTelemetry telemetry) {
        SaveRepository saves = SaveRepository.defaultRepository();
        publish(telemetry, holder.get());
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            while (true) {
                System.out.print("> ");
                System.out.flush();
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                String[] parts = line.strip().split("\\s+");
                String command = parts.length == 0 ? "" : parts[0].toLowerCase(
                        java.util.Locale.ROOT);
                if ("save".equals(command)) {
                    System.out.println(save(holder.get(), saves, parts));
                    continue;
                }
                if ("load".equals(command)) {
                    RuntimeSession loaded = load(saves, parts);
                    if (loaded != null) {
                        holder.set(loaded);
                        loaded.banner().forEach(System.out::println);
                        publish(telemetry, loaded);
                    }
                    continue;
                }
                if ("saves".equals(command)) {
                    System.out.println(listSaves(saves));
                    continue;
                }
                if ("png".equals(command)) {
                    System.out.println(renderPng(holder.get(), parts));
                    continue;
                }
                RuntimeSession session = holder.get();
                String out = session.execute(line);
                publish(telemetry, session);
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

    private static void publish(GameTelemetry telemetry, RuntimeSession session) {
        if (telemetry == null) {
            return;
        }
        telemetry.update(session.state());
        telemetry.setCommandCount(session.commandHistory().size());
    }

    private static String renderPng(RuntimeSession session, String[] parts) {
        Path output = parts.length > 1 ? Path.of(parts[1]) : Path.of("starmap.png");
        try {
            Path written = StarMapRenderer.render(session.state().graph(), output, 1280, 800);
            return "星图已生成：" + written;
        } catch (IOException | RuntimeException e) {
            return "星图生成失败：" + e.getMessage();
        }
    }

    private static String save(RuntimeSession session, SaveRepository saves, String[] parts) {
        if (parts.length < 2) {
            return "用法: save <槽位名> [口令]（槽位名仅限字母/数字/下划线/短横线）";
        }
        String password = parts.length > 2 ? parts[2] : null;
        try {
            String out = session.save(saves, parts[1], password);
            return out + "；读档命令：load " + parts[1]
                    + (password == null ? "" : " <口令>");
        } catch (IOException | IllegalArgumentException e) {
            return "保存失败：" + e.getMessage();
        }
    }

    private static RuntimeSession load(SaveRepository saves, String[] parts) {
        if (parts.length < 2) {
            return null;
        }
        String password = parts.length > 2 ? parts[2] : null;
        try {
            RuntimeSession loaded = RuntimeSession.load(saves, parts[1], password);
            System.out.println("读档成功：" + parts[1] + "（" + loaded.state().now()
                    + "，哈希 " + fc.sim.StateHasher.hash(loaded.state()) + "）");
            return loaded;
        } catch (IOException | IllegalArgumentException e) {
            System.out.println("读档失败：" + e.getMessage());
            return null;
        }
    }

    private static String listSaves(SaveRepository saves) {
        try {
            var slots = saves.slots();
            if (slots.isEmpty()) {
                return "存档目录 " + saves.directory() + " 暂无存档。";
            }
            return "---- 存档 (" + saves.directory() + ") ----\n  "
                    + String.join("\n  ", slots);
        } catch (IOException e) {
            return "无法读取存档目录：" + e.getMessage();
        }
    }
}
