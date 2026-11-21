package fc.tui;

import java.util.List;

/** 殖民地管理：等级、人口、归属与下一级升级要求。 */
public final class ColonyView {

    private ColonyView() {
    }

    public static int count(Snapshot snapshot) {
        return snapshot.colonies().size();
    }

    public static void render(ScreenBuffer buffer, Rect body, Snapshot snapshot, int selected,
                              CharSet chars) {
        Rect[] columns = body.splitColumns(Math.max(44, body.width() * 64 / 100));
        Rect list = Draw.panel(buffer, columns[0], "[03] 殖民地 / COLONY", chars);
        Rect side = Draw.panel(buffer, columns[1], "[SELECTED] 殖民地详情", chars);

        List<Snapshot.ColonyView> colonies = snapshot.colonies();
        if (colonies.isEmpty()) {
            Draw.line(buffer, list, 0, "尚无殖民地。", Style.PANEL_MUTED);
            Draw.line(buffer, side, 0, "使用 colony 命令建立前哨站。", Style.PANEL_MUTED);
            return;
        }
        Draw.line(buffer, list, 0,
                TextWidth.padRight("名称", 16) + TextWidth.padRight("等级", 8)
                        + TextWidth.padRight("人口", 8) + "归属",
                Style.PANEL_MUTED);
        for (int i = 0; i < colonies.size() && i + 1 < list.height(); i++) {
            Snapshot.ColonyView colony = colonies.get(i);
            boolean chosen = i == clamp(selected, colonies.size());
            String row = TextWidth.padRight(colony.name(), 16)
                    + TextWidth.padRight(colony.level() + " 级", 8)
                    + TextWidth.padRight(Integer.toString(colony.population()), 8)
                    + (colony.playerOwned() ? "玩家" : "势力");
            Draw.row(buffer, list, i + 1, row, chosen, Style.PANEL, chars);
        }

        Snapshot.ColonyView colony = colonies.get(clamp(selected, colonies.size()));
        int row = 0;
        Draw.line(buffer, side, row++, colony.name(), Style.PANEL_ACCENT);
        Draw.kv(buffer, side, row++, "编号", "L" + colony.id(),
                Style.PANEL_MUTED, Style.PANEL);
        Draw.kv(buffer, side, row++, "等级", colony.level() + " / 3",
                Style.PANEL_MUTED, Style.PANEL_OK);
        Draw.kv(buffer, side, row++, "人口", Integer.toString(colony.population()),
                Style.PANEL_MUTED, Style.PANEL);
        Draw.kv(buffer, side, row++, "归属", colony.playerOwned() ? "玩家" : "势力",
                Style.PANEL_MUTED, Style.PANEL);
        for (String line : Draw.wrap(colony.nextUpgrade(), side.width())) {
            if (row >= side.height()) {
                break;
            }
            Draw.line(buffer, side, row++, line,
                    colony.nextUpgrade().equals("已满级") ? Style.PANEL_OK : Style.PANEL_WARN);
        }
        if (row + 1 < side.height()) {
            Draw.line(buffer, side, row + 1, "colony upgrade <地点> 可升级（需物资/信用点）",
                    Style.PANEL_MUTED);
        }
    }

    public static String detail(Snapshot snapshot, int selected) {
        List<Snapshot.ColonyView> colonies = snapshot.colonies();
        if (colonies.isEmpty()) {
            return "尚无殖民地。";
        }
        Snapshot.ColonyView colony = colonies.get(clamp(selected, colonies.size()));
        return colony.name() + "  (" + (colony.playerOwned() ? "玩家" : "势力") + ")\n"
                + "编号：L" + colony.id() + "\n"
                + "等级：" + colony.level() + " / 3\n"
                + "人口：" + colony.population() + "\n"
                + "下一级：" + colony.nextUpgrade();
    }

    private static int clamp(int selected, int size) {
        return size <= 0 ? 0 : Math.max(0, Math.min(size - 1, selected));
    }
}
