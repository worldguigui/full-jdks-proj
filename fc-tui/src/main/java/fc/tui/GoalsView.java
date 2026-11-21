package fc.tui;

import java.util.List;

/** 目标与胜利路线页：直接渲染宿主提供的 GoalService 面板。 */
public final class GoalsView {

    private GoalsView() {
    }

    public static void render(ScreenBuffer buffer, Rect body, Snapshot snapshot, CharSet chars) {
        Rect panel = Draw.panel(buffer, body, "目标与胜利路线 / GOALS", chars);
        List<String> lines = Draw.wrap(snapshot.goalsReport(), panel.width());
        if (lines.isEmpty()) {
            Draw.line(buffer, panel, 0, "目标数据不可用。", Style.PANEL_MUTED);
            return;
        }
        for (int i = 0; i < lines.size() && i < panel.height(); i++) {
            Draw.line(buffer, panel, i, lines.get(i),
                    lines.get(i).contains("★") ? Style.PANEL_WARN : Style.PANEL);
        }
    }
}
