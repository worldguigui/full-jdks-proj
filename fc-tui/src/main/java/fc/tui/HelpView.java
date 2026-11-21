package fc.tui;

import java.util.ArrayList;
import java.util.List;

/**
 * 帮助页：直接展示 CLI {@code help} 的输出，并在页首补充 TUI 操作。
 * 输出由 TuiApp 在命令执行后传入；长内容按面板宽度换行并可滚动。
 */
public final class HelpView {

    private static final List<String> HEADERS = List.of(
            "页面命令（直接输入）：map ships colonies research market log status decisions goals help output",
            "列表：select <N|up|down>  inspect  back    推进：next <天数>",
            "滚动：scroll up/down [N]  scroll top/bottom  page up/down",
            "tui off 返回 CLI（从 CLI 内 tui on 启动时）；空行/refresh 同步其他窗口。");

    private HelpView() {
    }

    /** 完整可滚动内容；TuiApp 也用它计算滚动范围。 */
    public static List<String> contentLines(List<String> cliHelp, int width) {
        List<String> lines = new ArrayList<>();
        for (String header : HEADERS) {
            lines.addAll(Draw.wrap(header, width));
        }
        lines.add("");
        if (cliHelp == null || cliHelp.isEmpty()) {
            lines.add("完整 CLI 帮助请在独立命令行窗口输入 help；本页只显示可视化提示。");
        } else {
            for (String raw : cliHelp) {
                lines.addAll(Draw.wrap(raw, width));
            }
        }
        return lines;
    }

    public static void render(ScreenBuffer buffer, Rect body, List<String> cliHelp,
                              int scroll, CharSet chars) {
        int width = Math.max(1, body.width() - 2);
        List<String> lines = contentLines(cliHelp, width);
        int headerLines = headerLines(width);
        Rect panel = Draw.panel(buffer, body,
                "帮助 / HELP" + Draw.range(scroll, panelRows(body), lines.size()), chars);
        List<String> window = Draw.slice(lines, scroll, panel.height());
        for (int i = 0; i < window.size(); i++) {
            Draw.line(buffer, panel, i, window.get(i),
                    scroll + i < headerLines ? Style.PANEL_ACCENT : Style.PANEL);
        }
    }

    private static int headerLines(int width) {
        int count = 1;
        for (String header : HEADERS) {
            count += Draw.wrap(header, width).size();
        }
        return count;
    }

    private static int panelRows(Rect body) {
        return Math.max(1, body.height() - 2);
    }
}
