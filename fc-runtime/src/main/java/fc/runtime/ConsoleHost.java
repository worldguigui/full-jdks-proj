package fc.runtime;

import fc.persist.SaveRepository;
import fc.tui.TuiGame;
import fc.world.WorldState;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 双窗口模式下的权威命令宿主：TUI 只读快照，独立 CLI 窗口通过本地套接字
 * 调用本类执行命令。所有会话替换（load）与命令执行都在同一把锁内完成，
 * 避免 TUI 线程读到半个世界。
 */
final class ConsoleHost implements TuiGame {

    private final AtomicReference<RuntimeSession> current;
    private final SaveRepository saves;
    private final Consumer<RuntimeSession> telemetryPublisher;
    private final Consumer<String> commandObserver;
    private final Runnable stopCallback;

    ConsoleHost(RuntimeSession initial, SaveRepository saves,
                Consumer<RuntimeSession> telemetryPublisher,
                Consumer<String> commandObserver, Runnable stopCallback) {
        this.current = new AtomicReference<>(initial);
        this.saves = saves;
        this.telemetryPublisher = telemetryPublisher;
        this.commandObserver = commandObserver;
        this.stopCallback = stopCallback;
    }

    @Override
    public synchronized WorldState state() {
        return current.get().state();
    }

    @Override
    public Object lock() {
        return this;
    }

    @Override
    public synchronized String execute(String command) {
        String[] parts = command.strip().split("\\s+");
        String head = parts.length == 0 ? "" : parts[0].toLowerCase(Locale.ROOT);
        String output;
        switch (head) {
            case "save" -> output = save(parts);
            case "load" -> output = load(parts);
            case "saves" -> output = listSaves();
            case "png" -> output = renderPng(parts);
            default -> {
                RuntimeSession session = current.get();
                output = session.execute(command);
                publish(session);
            }
        }
        if (commandObserver != null) {
            commandObserver.accept(command.strip());
        }
        return output;
    }

    synchronized List<String> banner() {
        return current.get().banner();
    }

    synchronized RuntimeSession session() {
        return current.get();
    }

    @Override
    public synchronized String marketReport() {
        return current.get().marketReport();
    }

    @Override
    public synchronized String researchReport() {
        return current.get().researchReport();
    }

    @Override
    public synchronized String technologiesReport() {
        return current.get().technologiesReport();
    }

    /** CLI 的 quit/exit：关闭宿主，让 TUI 退出并由 Main 收尾。 */
    void stop() {
        if (stopCallback != null) {
            stopCallback.run();
        }
    }

    private String save(String[] parts) {
        if (parts.length < 2) {
            return "用法: save <槽位名> [口令]（槽位名仅限字母/数字/下划线/短横线）";
        }
        String password = parts.length > 2 ? parts[2] : null;
        try {
            String out = current.get().save(saves, parts[1], password);
            return out + "；读档命令：load " + parts[1]
                    + (password == null ? "" : " <口令>");
        } catch (IOException | IllegalArgumentException e) {
            return "保存失败：" + e.getMessage();
        }
    }

    private String load(String[] parts) {
        if (parts.length < 2) {
            return "用法: load <槽位名> [口令]";
        }
        String password = parts.length > 2 ? parts[2] : null;
        try {
            RuntimeSession loaded = RuntimeSession.load(saves, parts[1], password);
            current.set(loaded);
            publish(loaded);
            return "读档成功：" + parts[1] + "（" + loaded.state().now()
                    + "，哈希 " + fc.sim.StateHasher.hash(loaded.state()) + "）";
        } catch (IOException | IllegalArgumentException e) {
            return "读档失败：" + e.getMessage();
        }
    }

    private String listSaves() {
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

    private String renderPng(String[] parts) {
        java.nio.file.Path output = parts.length > 1
                ? java.nio.file.Path.of(parts[1]) : java.nio.file.Path.of("starmap.png");
        try {
            java.nio.file.Path written = fc.extensions.StarMapRenderer.render(
                    current.get().state().graph(), output, 1280, 800);
            return "星图已生成：" + written;
        } catch (IOException | RuntimeException e) {
            return "星图生成失败：" + e.getMessage();
        }
    }

    private void publish(RuntimeSession session) {
        if (telemetryPublisher != null) {
            telemetryPublisher.accept(session);
        }
    }
}
