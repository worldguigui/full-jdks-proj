package fc.tui;

import java.util.List;

/** 命令输出页：显示本轮 TUI 会话的完整命令输出，支持滚动。 */
public final class OutputView {

    private OutputView() {
    }

    public static void render(ScreenBuffer buffer, Rect body, List<String> lines, int scroll,
                              CharSet chars) {
        Rect panel = Draw.panel(buffer, body,
                "命令输出 / OUTPUT"
                        + Draw.range(scroll, Math.max(1, body.height() - 2), lines.size()),
                chars);
        if (lines.isEmpty()) {
            Draw.line(buffer, panel, 0, "暂无命令输出。", Style.PANEL_MUTED);
            Draw.line(buffer, panel, 1, "执行命令后可用 output 查看完整输出。",
                    Style.PANEL_MUTED);
            return;
        }
        List<String> window = Draw.slice(lines, scroll, panel.height());
        for (int i = 0; i < window.size(); i++) {
            String line = window.get(i);
            Draw.line(buffer, panel, i, line,
                    line.startsWith("> ") ? Style.PANEL_ACCENT : Style.PANEL);
        }
    }
}
