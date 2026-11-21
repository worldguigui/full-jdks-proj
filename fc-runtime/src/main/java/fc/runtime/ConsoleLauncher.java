package fc.runtime;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 为双窗口模式打开独立的 CLI 窗口。
 *
 * <p>Windows 用 {@code cmd /c start} 新建控制台；其他平台打印可直接复制运行的
 * 命令（用户可自行开终端），不假装已经跨平台解决终端窗口创建。</p>
 */
final class ConsoleLauncher {

    private ConsoleLauncher() {
    }

    static void launch(int port, String token, PrintStream info) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String java = Path.of(System.getProperty("java.home"), "bin",
                os.contains("win") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("java.class.path");
        String[] clientArgs = {
                java, "-Dfile.encoding=UTF-8",
                "-Dstdin.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                "-cp", classpath,
                "fc.runtime.ConsoleClient",
                "--host", "127.0.0.1",
                "--port", Integer.toString(port),
                "--token", token
        };
        boolean noWindow = "1".equals(System.getenv("FC_CONSOLE_NO_WINDOW"));
        if (os.contains("win") && !noWindow) {
            try {
                // 新控制台默认可能是 936；先 chcp 65001，否则 UTF-8 输出会乱码。
                StringBuilder bat = new StringBuilder();
                bat.append("@echo off\r\n");
                bat.append("chcp 65001 >nul\r\n");
                bat.append("title Frontier Chronicle CLI\r\n");
                for (String arg : clientArgs) {
                    bat.append(quote(arg)).append(' ');
                }
                bat.append("\r\necho.\r\npause\r\n");
                Path script = Files.createTempFile("fc-console-", ".bat");
                Files.writeString(script, bat.toString(),
                        StandardCharsets.US_ASCII);
                script.toFile().deleteOnExit();
                // 最小化启动：Windows Terminal/控制台默认会把新窗口切到前台，
                // 导致用户输入落到 CLI 窗口，主 TUI 看起来“无法输入”。
                new ProcessBuilder("cmd.exe", "/c", "start",
                        "Frontier Chronicle CLI", "/min", script.toString()).start();
                info.println("CLI 窗口已最小化启动，避免抢走 TUI 输入焦点；"
                        + "需要完整输出时请从任务栏恢复。");
            } catch (Exception e) {
                info.println("无法自动打开 CLI 窗口：" + e.getMessage());
            }
        }
        StringBuilder manual = new StringBuilder();
        for (String arg : clientArgs) {
            if (!manual.isEmpty()) {
                manual.append(' ');
            }
            manual.append(quote(arg));
        }
        info.println("CLI 客户端命令（若独立窗口未出现，可在另一终端运行）：");
        if (os.contains("win")) {
            info.println("  chcp 65001");
        }
        info.println("  " + manual);
    }

    private static String quote(String arg) {
        return arg.contains(" ") ? "\"" + arg + "\"" : arg;
    }
}
