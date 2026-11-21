package fc.runtime;

import java.io.PrintStream;
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
                java, "-Dfile.encoding=UTF-8", "-cp", classpath,
                "fc.runtime.ConsoleClient",
                "--host", "127.0.0.1",
                "--port", Integer.toString(port),
                "--token", token
        };
        boolean noWindow = "1".equals(System.getenv("FC_CONSOLE_NO_WINDOW"));
        if (os.contains("win") && !noWindow) {
            try {
                String[] command = new String[clientArgs.length + 4];
                command[0] = "cmd.exe";
                command[1] = "/c";
                command[2] = "start";
                command[3] = "Frontier Chronicle CLI";
                System.arraycopy(clientArgs, 0, command, 4, clientArgs.length);
                new ProcessBuilder(command).start();
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
        info.println("  " + manual);
    }

    private static String quote(String arg) {
        return arg.contains(" ") ? "\"" + arg + "\"" : arg;
    }
}
