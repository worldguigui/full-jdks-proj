package fc.tui;

import java.util.ArrayList;
import java.util.List;

/**
 * 帮助页：直接展示 CLI {@code help} 的输出，并在页首补充 TUI 操作。
 * 输出由 TuiApp 在命令执行后传入；长内容按面板宽度换行并可滚动。
 */
public final class HelpView {

    private static final List<String> HEADERS = List.of(
            "页面命令：map/ships/colonies/research/market/log/status/decisions/goals/help 切换页面；"
                    + "output 查看完整命令输出",
            "列表：select <N|up|down> 移动选中项（自动滚入视口）；"
                    + "inspect 打开详情；back 关闭详情或返回舰桥",
            "滚动：scroll up|down [N] 逐行；scroll top|bottom 跳到顶部/底部；"
                    + "page up|down 按整屏滚动",
            "输出：help/goals/报告/详情均可滚动；tui off 返回 CLI；空行/refresh 同步其他窗口。");

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
