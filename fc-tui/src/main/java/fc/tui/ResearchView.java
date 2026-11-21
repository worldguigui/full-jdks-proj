package fc.tui;

import java.util.List;

/** 科研中心：已解锁科技、进行中的项目与科研报告。 */
public final class ResearchView {

    private ResearchView() {
    }

    /** 科研状态面板的可滚动内容。 */
    public static List<String> reportLines(String reportText, Rect body) {
        Rect right = body.splitColumns(Math.max(36, body.width() / 2))[1];
        Rect reportPanel = right.splitRows(
                Math.max(8, right.height() * 60 / 100))[1];
        return Draw.wrap(reportText, Math.max(1, reportPanel.inset(1, 1).width()));
    }

    /** 科研状态面板内部可视行数。 */
    public static int reportVisibleRows(Rect body) {
        Rect right = body.splitColumns(Math.max(36, body.width() / 2))[1];
        Rect reportPanel = right.splitRows(
                Math.max(8, right.height() * 60 / 100))[1];
        return Math.max(1, reportPanel.inset(1, 1).height());
    }

    public static void render(ScreenBuffer buffer, Rect body, Snapshot snapshot, int scroll,
                              CharSet chars) {
        Rect[] columns = body.splitColumns(Math.max(36, body.width() / 2));
        Rect tech = Draw.panel(buffer, columns[0], "[04] 科技树 / RESEARCH", chars);
        Rect right = columns[1];
        Rect jobs = Draw.panel(buffer, right.splitRows(Math.max(8, right.height() * 60 / 100))[0],
                "进行中的项目", chars);
        List<String> reportText = reportLines(snapshot.researchReport(), body);
        Rect report = Draw.panel(buffer,
                right.splitRows(Math.max(8, right.height() * 60 / 100))[1],
                "科研状态" + Draw.range(scroll, reportVisibleRows(body), reportText.size()), chars);

        Draw.line(buffer, tech, 0, "已解锁 " + snapshot.techs().size() + " 项科技",
                Style.PANEL_ACCENT);
        int row = 2;
        for (String techId : snapshot.techs()) {
            if (row >= tech.height()) {
                break;
            }
            Draw.line(buffer, tech, row++, "  " + chars.bullet() + " " + techId,
                    Style.PANEL_OK);
        }
        if (snapshot.techs().isEmpty()) {
            Draw.line(buffer, tech, 2, "尚未解锁科技。", Style.PANEL_MUTED);
            Draw.line(buffer, tech, 3, "research start <科技> <实验室> 开始研究。",
                    Style.PANEL_MUTED);
        }

        if (snapshot.researchJobs().isEmpty()) {
            Draw.line(buffer, jobs, 0, "没有进行中的项目。", Style.PANEL_MUTED);
        } else {
            int jobRow = 0;
            for (Snapshot.ResearchJob job : snapshot.researchJobs()) {
                if (jobRow >= jobs.height()) {
                    break;
                }
                Draw.line(buffer, jobs, jobRow++, TextWidth.padRight(job.lab(), 16)
                        + job.tech(), Style.PANEL_WARN);
            }
        }

        List<String> window = Draw.slice(reportText, scroll, report.height());
        for (int i = 0; i < window.size(); i++) {
            Draw.line(buffer, report, i, window.get(i), Style.PANEL_MUTED);
        }
    }
}
