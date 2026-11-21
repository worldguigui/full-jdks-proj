package fc.tui;

import java.util.ArrayList;
import java.util.List;

/** 情报中心：编年史事件、势力关系与待处理事项。 */
public final class IntelView {

    private IntelView() {
    }

    public static int count(Snapshot snapshot) {
        return snapshot.events().size();
    }

    public static void render(ScreenBuffer buffer, Rect body, Snapshot snapshot, int selected,
                              CharSet chars) {
        Rect[] columns = body.splitColumns(Math.max(44, body.width() * 62 / 100));
        Rect events = Draw.panel(buffer, columns[0], "[06] 情报中心 / INTELLIGENCE", chars);
        Rect right = columns[1];
        Rect[] split = right.splitRows(Math.max(10, right.height() * 62 / 100));
        Rect factions = Draw.panel(buffer, split[0], "势力关系", chars);
        Rect decisions = Draw.panel(buffer, split[1], "待处理事项", chars);

        Draw.line(buffer, events, 0,
                TextWidth.padRight("日", 5) + TextWidth.padRight("类别", 10) + "摘要",
                Style.PANEL_MUTED);
        List<Snapshot.EventRow> newest = newestFirst(snapshot);
        for (int i = 0; i < newest.size() && i + 1 < events.height(); i++) {
            Snapshot.EventRow event = newest.get(i);
            boolean chosen = i == clamp(selected, newest.size());
            String row = TextWidth.padRight("D" + event.day(), 5)
                    + TextWidth.padRight(event.category(), 10) + event.summary();
            Draw.row(buffer, events, i + 1, row, chosen, Style.PANEL, chars);
        }
        if (newest.isEmpty()) {
            Draw.line(buffer, events, 1, "编年史暂无事件。", Style.PANEL_MUTED);
        }

        int row = 0;
        for (Snapshot.FactionView faction : snapshot.factions()) {
            if (row >= factions.height()) {
                break;
            }
            Draw.line(buffer, factions, row++, TextWidth.padRight(faction.name(), 12)
                            + TextWidth.padRight(faction.kind(), 8)
                            + "声望 " + faction.reputation() + "｜" + faction.treaties(),
                    Style.PANEL);
        }
        if (snapshot.factions().isEmpty()) {
            Draw.line(buffer, factions, 0, "尚无势力数据。", Style.PANEL_MUTED);
        }

        if (snapshot.decisions().isEmpty()) {
            Draw.line(buffer, decisions, 0, "没有待处理事项。", Style.PANEL_OK);
        } else {
            int decisionRow = 0;
            for (String decision : snapshot.decisions()) {
                if (decisionRow >= decisions.height()) {
                    break;
                }
                Draw.line(buffer, decisions, decisionRow++, chars.warn() + " " + decision,
                        Style.PANEL_WARN);
            }
        }
        if (snapshot.ending() != null && !snapshot.ending().isBlank()) {
            Draw.line(buffer, decisions, decisions.height() - 1,
                    "结局：" + snapshot.ending(), Style.PANEL_ACCENT);
        }
    }

    public static String detail(Snapshot snapshot, int selected) {
        List<Snapshot.EventRow> newest = newestFirst(snapshot);
        if (newest.isEmpty()) {
            return "编年史暂无事件。";
        }
        Snapshot.EventRow event = newest.get(clamp(selected, newest.size()));
        return "日 " + event.day() + "｜" + event.category() + "\n"
                + event.type() + "\n\n" + event.summary();
    }

    private static List<Snapshot.EventRow> newestFirst(Snapshot snapshot) {
        List<Snapshot.EventRow> newest = new ArrayList<>(snapshot.events());
        newest.sort((a, b) -> Long.compare(b.day(), a.day()));
        return newest;
    }

    private static int clamp(int selected, int size) {
        return size <= 0 ? 0 : Math.max(0, Math.min(size - 1, selected));
    }
}
