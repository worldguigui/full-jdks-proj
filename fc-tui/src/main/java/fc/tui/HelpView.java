package fc.tui;

import java.util.List;

/**
 * 帮助页：直接展示 CLI {@code help} 的输出，并在页首补充 TUI 列表操作。
 * 输出由 TuiApp 在命令执行后传入，不在渲染期再次执行命令。
 */
public final class HelpView {

    private HelpView() {
    }

    public static void render(ScreenBuffer buffer, Rect body, List<String> cliHelp,
                              CharSet chars) {
        Rect panel = Draw.panel(buffer, body, "帮助 / HELP", chars);
        int row = 0;
        Draw.line(buffer, panel, row++,
                "页面命令（直接输入）：map ships colonies research market log status decisions help",
                Style.PANEL_ACCENT);
        Draw.line(buffer, panel, row++,
                "列表：select <N|up|down>  inspect  back    推进：next <天数>    退出：quit",
                Style.PANEL_MUTED);
        Draw.line(buffer, panel, row++, "以下为 CLI help 输出：", Style.PANEL_MUTED);
        row++;

        if (cliHelp == null || cliHelp.isEmpty()) {
            Draw.line(buffer, panel, row,
                    "完整 CLI 帮助请在独立命令行窗口输入 help；本页只显示可视化提示。",
                    Style.PANEL);
            return;
        }
        int width = Math.max(1, panel.width());
        for (int i = 0; i < cliHelp.size() && row < panel.height(); i++) {
            List<String> wrapped = Draw.wrap(cliHelp.get(i), width);
            for (String line : wrapped) {
                if (row >= panel.height()) {
                    break;
                }
                Draw.line(buffer, panel, row++, line, Style.PANEL);
            }
        }
    }
}
