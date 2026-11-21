package fc.tui;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 真实终端：UTF-8 输出、备用屏幕、尺寸探测与字符能力降级。
 *
 * <p>Java SE 21 没有标准 Raw Mode，本类有意只做「输出侧全屏 + 行输入」；
 * 输入由 {@link TuiApp} 通过标准输入逐行读取。ANSI 不可用或 {@code FC_TUI_ASCII=1}
 * 时降级为 ASCII 线框；{@code NO_COLOR} 关闭真彩。终端尺寸在每次输入后刷新，
 * 支持 {@code FC_TUI_COLS}/{@code FC_TUI_ROWS} 覆盖。</p>
 */
public final class SystemTerminal implements Terminal {

    private final PrintStream output;
    private final boolean unicode;
    private final boolean color;
    private TerminalSize cached;

    public SystemTerminal() {
        this(new PrintStream(new FileOutputStream(FileDescriptor.out), true,
                StandardCharsets.UTF_8));
    }

    public SystemTerminal(PrintStream output) {
        this.output = output;
        this.unicode = detectUnicode();
        this.color = detectColor();
        this.cached = TerminalSize.detect();
    }

    @Override
    public TerminalSize size() {
        return cached;
    }

    /** 重新探测尺寸；仅读取环境覆盖，避免每次输入都启动子进程。 */
    public void refreshSize() {
        TerminalSize overridden = TerminalSize.fromEnvironment();
        if (overridden != null) {
            cached = overridden;
        }
    }

    @Override
    public boolean unicode() {
        return unicode;
    }

    @Override
    public boolean color() {
        return color;
    }

    @Override
    public void enter() {
        write(Ansi.ALT_SCREEN_ON + Ansi.CLEAR + Ansi.WRAP_OFF);
        flush();
    }

    @Override
    public void exit() {
        write(Ansi.RESET + Ansi.CURSOR_SHOW + Ansi.WRAP_ON + Ansi.ALT_SCREEN_OFF);
        flush();
    }

    @Override
    public void write(String text) {
        output.print(text);
    }

    @Override
    public void flush() {
        output.flush();
    }

    private static boolean detectUnicode() {
        if ("1".equals(System.getenv("FC_TUI_ASCII"))) {
            return false;
        }
        String term = System.getenv("TERM");
        return term == null || !term.toLowerCase(Locale.ROOT).contains("dumb");
    }

    private static boolean detectColor() {
        if ("1".equals(System.getenv("NO_COLOR"))) {
            return false;
        }
        String term = System.getenv("TERM");
        return term == null || !term.toLowerCase(Locale.ROOT).contains("dumb");
    }
}
