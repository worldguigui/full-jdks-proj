package fc.tui;

import java.util.List;

/** 目标与胜利路线页：直接渲染宿主提供的 GoalService 面板。 */
public final class GoalsView {

    private GoalsView() {
    }

    public static void render(ScreenBuffer buffer, Rect body, Snapshot snapshot, int scroll,
                              CharSet chars) {
        int width = Math.max(1, body.width() - 2);
        List<String> lines = Draw.wrap(snapshot.goalsReport(), width);
        Rect panel = Draw.panel(buffer, body,
                "目标与胜利路线 / GOALS"
                        + Draw.range(scroll, Math.max(1, body.height() - 2), lines.size()),
                chars);
        if (lines.isEmpty()) {
            Draw.line(buffer, panel, 0, "目标数据不可用。", Style.PANEL_MUTED);
            return;
        }
        List<String> window = Draw.slice(lines, scroll, panel.height());
        for (int i = 0; i < window.size(); i++) {
            String line = window.get(i);
            Draw.line(buffer, panel, i, line,
                    line.contains("★") ? Style.PANEL_WARN : Style.PANEL);
        }
    }
}
