package fc.runtime;

import fc.tui.SystemTerminal;
import fc.tui.TuiApp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 独立命令行窗口：所有命令经回环连接交给主进程的权威宿主执行。
 *
 * <p>输入 {@code tui on} 可在本窗口用同一连接获取只读快照并开启可视化 TUI；
 * {@code tui off} 返回 CLI。输入 {@code window} 可再派生一个同样接入当前世界的
 * 窗口。TUI 与 CLI 共用同一个输入 Reader，避免缓冲输入丢失。</p>
 */
public final class ConsoleClient {

    private ConsoleClient() {
    }

    public static void main(String[] args) {
        ensureUtf8Console();
        String host = "127.0.0.1";
        int port = -1;
        String token = "";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = next(args, ++i, args[i]);
                case "--port" -> port = Integer.parseInt(next(args, ++i, args[i]));
                case "--token" -> token = next(args, ++i, args[i]);
                default -> {
                    System.err.println("未知参数：" + args[i]);
                    return;
                }
            }
        }
        if (port <= 0 || token.isEmpty()) {
            System.err.println("用法：ConsoleClient --host 127.0.0.1 --port <端口> --token <令牌>");
            System.exit(2);
        }
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        try (RemoteConsole remote = RemoteConsole.connect(host, port, token);
             BufferedReader keyboard = new BufferedReader(new InputStreamReader(
                     System.in, StandardCharsets.UTF_8))) {
            for (String line : remote.greeting()) {
                out.println(line);
            }
            out.println();
            out.println("提示：输入 help/log 等命令；tui on 在本窗口开启可视化；"
                    + "window 再开一个窗口；quit 关闭本控制台。");
            while (true) {
                out.print("> ");
                out.flush();
                String command = keyboard.readLine();
                if (command == null) {
                    break;
                }
                String trimmed = command.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String lower = trimmed.toLowerCase(Locale.ROOT);
                if ("quit".equals(lower) || "exit".equals(lower)) {
                    remote.quit();
                    out.println("指挥链路已关闭。");
                    break;
                }
                if ("window".equals(lower)) {
                    ConsoleLauncher.launch(port, token, out);
                    continue;
                }
                if ("tui on".equals(lower) || "tui".equals(lower)) {
                    TuiApp app = new TuiApp(new RemoteTuiGame(remote), new SystemTerminal());
                    app.run(keyboard);
                    if (app.gameQuit()) {
                        remote.quit();
                        out.println("指挥链路已关闭。");
                        break;
                    }
                    out.println("已返回 CLI（本窗口仍连接同一世界）。");
                    continue;
                }
                if ("tui off".equals(lower) || "tui status".equals(lower)) {
                    out.println("TUI 未开启（输入 tui on 在本窗口进入可视化界面）。");
                    continue;
                }
                try {
                    String output = remote.command(trimmed);
                    out.print(output);
                    if (!output.isEmpty() && !output.endsWith("\n")) {
                        out.println();
                    }
                } catch (IOException e) {
                    out.println("会话结束：" + e.getMessage());
                    break;
                }
            }
        } catch (IOException e) {
            out.println("无法连接指挥终端：" + e.getMessage());
            System.exit(1);
        }
    }

    /** Windows 控制台可能仍是 936；尝试切到 UTF-8，失败时保持原状。 */
    private static void ensureUtf8Console() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }
        try {
            Process process = new ProcessBuilder("cmd.exe", "/c", "chcp 65001 >nul")
                    .inheritIO()
                    .start();
            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 无控制台或受限环境：继续使用显式 UTF-8 流。
        }
    }

    private static String next(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " 需要参数");
        }
        return args[index];
    }
}
