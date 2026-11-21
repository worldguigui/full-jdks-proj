package fc.tui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 终端行列数。优先读环境变量覆盖，其次尝试平台命令，最后使用保守的默认值。
 *
 * <p>Java SE 21 没有标准的终端尺寸 API；Windows 用 {@code mode con}，Unix 用
 * {@code stty size}（需能打开 /dev/tty）。所有探测都有短超时和失败回退，
 * 自动化环境不会因此挂起。{@code FC_TUI_COLS}/{@code FC_TUI_ROWS} 可用于
 * 测试或终端模拟器覆盖。</p>
 */
public record TerminalSize(int columns, int rows) {

    public static final int MIN_COLUMNS = 80;
    public static final int MIN_ROWS = 24;
    public static final TerminalSize MINIMUM = new TerminalSize(MIN_COLUMNS, MIN_ROWS);
    public static final TerminalSize DEFAULT = new TerminalSize(120, 34);

    private static final Pattern WINDOWS_COLUMNS = Pattern.compile(
            "(?i)(columns|列数|欄數)\\s*[:：]?\\s*(\\d+)");
    private static final Pattern WINDOWS_ROWS = Pattern.compile(
            "(?i)(lines|行数|行數)\\s*[:：]?\\s*(\\d+)");

    public TerminalSize {
        columns = Math.max(MIN_COLUMNS, Math.min(500, columns));
        rows = Math.max(MIN_ROWS, Math.min(300, rows));
    }

    /** 读取覆盖值或探测当前终端。 */
    public static TerminalSize detect() {
        TerminalSize overridden = fromEnvironment();
        if (overridden != null) {
            return overridden;
        }
        TerminalSize detected = detectPlatform();
        return detected == null ? DEFAULT : detected;
    }

    /** 仅读取显式覆盖（FC_TUI_COLS/ROWS 或 COLUMNS/LINES），不启动任何进程。 */
    public static TerminalSize fromEnvironment() {
        int cols = positiveInt(System.getenv("FC_TUI_COLS"), -1);
        int rows = positiveInt(System.getenv("FC_TUI_ROWS"), -1);
        if (cols > 0 && rows > 0) {
            return new TerminalSize(cols, rows);
        }
        int envCols = positiveInt(System.getenv("COLUMNS"), -1);
        int envRows = positiveInt(System.getenv("LINES"), -1);
        if (envCols > 0 && envRows > 0) {
            return new TerminalSize(envCols, envRows);
        }
        return null;
    }

    /** 探测平台尺寸；失败返回 null。 */
    public static TerminalSize detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                return detectWindows();
            }
            return detectUnix();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static TerminalSize detectWindows() throws IOException {
        String output = run(1_500, "cmd.exe", "/c", "mode con");
        if (output == null) {
            return null;
        }
        int cols = match(WINDOWS_COLUMNS, output);
        int rows = match(WINDOWS_ROWS, output);
        return cols > 0 && rows > 0 ? new TerminalSize(cols, rows) : null;
    }

    private static TerminalSize detectUnix() throws IOException {
        String output = run(1_500, "/bin/sh", "-c", "stty size < /dev/tty");
        if (output == null) {
            return null;
        }
        String[] parts = output.strip().split("\\s+");
        if (parts.length < 2) {
            return null;
        }
        try {
            int rows = Integer.parseInt(parts[0]);
            int cols = Integer.parseInt(parts[1]);
            return cols > 0 && rows > 0 ? new TerminalSize(cols, rows) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String run(long timeoutMillis, String... command) throws IOException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            Charset charset = Charset.forName(
                    System.getProperty("native.encoding", "UTF-8"));
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), charset))) {
                StringBuilder out = new StringBuilder();
                for (String line; (line = reader.readLine()) != null; ) {
                    out.append(line).append('\n');
                    if (out.length() > 8_192) {
                        break;
                    }
                }
                return out.toString();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return null;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static int match(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(2)) : -1;
    }

    private static int positiveInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.strip());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
