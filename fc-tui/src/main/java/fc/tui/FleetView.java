package fc.tui;

import java.util.List;

/** 舰队管理：舰船列表、耐久/货舱/航程与详情。 */
public final class FleetView {

    private FleetView() {
    }

    public static int count(Snapshot snapshot) {
        return snapshot.ships().size();
    }

    public static void render(ScreenBuffer buffer, Rect body, Snapshot snapshot, int selected,
                              int scroll, CharSet chars) {
        int rows = Math.max(1, body.height() - 3);
        List<Snapshot.ShipView> all = snapshot.ships();
        int first = Math.max(0, Math.min(scroll, Math.max(0, all.size() - rows)));
        Rect[] columns = body.splitColumns(Math.max(44, body.width() * 64 / 100));
        Rect list = Draw.panel(buffer, columns[0],
                "[02] 舰队状态 / FLEET" + Draw.range(first, rows, all.size()), chars);
        Rect side = Draw.panel(buffer, columns[1], "[SELECTED] 舰船详情", chars);

        List<Snapshot.ShipView> ships = snapshot.ships();
        if (ships.isEmpty()) {
            Draw.line(buffer, list, 0, "当前没有舰船。", Style.PANEL_MUTED);
            Draw.line(buffer, side, 0, "在船坞建造舰船后即可编队。", Style.PANEL_MUTED);
            return;
        }
        Draw.line(buffer, list, 0,
                TextWidth.padRight("舰船", 12) + TextWidth.padRight("类型", 10)
                        + TextWidth.padRight("耐久", 14) + "状态",
                Style.PANEL_MUTED);
        for (int i = first; i < ships.size() && i - first + 1 < list.height(); i++) {
            Snapshot.ShipView ship = ships.get(i);
            boolean chosen = i == clamp(selected, ships.size());
            String row = TextWidth.padRight(ship.name(), 12)
                    + TextWidth.padRight(ship.type(), 10)
                    + TextWidth.padRight(ship.hull() + "/" + ship.maxHull()
                            + " " + Draw.percent(ship.hull(), ship.maxHull()) + "%", 14)
                    + ship.voyage();
            Draw.row(buffer, list, i - first + 1, row, chosen, Style.PANEL, chars);
        }

        Snapshot.ShipView ship = ships.get(clamp(selected, ships.size()));
        int row = 0;
        Draw.line(buffer, side, row++, ship.name(), Style.PANEL_ACCENT);
        Draw.kv(buffer, side, row++, "编号", ship.id(), Style.PANEL_MUTED, Style.PANEL);
        Draw.kv(buffer, side, row++, "类型", ship.type(), Style.PANEL_MUTED, Style.PANEL);
        Draw.kv(buffer, side, row++, "位置", ship.location(), Style.PANEL_MUTED, Style.PANEL);
        Draw.kv(buffer, side, row++, "耐久", ship.hull() + "/" + ship.maxHull(),
                Style.PANEL_MUTED,
                ship.hull() * 2 < ship.maxHull() ? Style.PANEL_WARN : Style.PANEL_OK);
        Draw.kv(buffer, side, row++, "船员上限", Integer.toString(ship.crewCapacity()),
                Style.PANEL_MUTED, Style.PANEL);
        Draw.kv(buffer, side, row++, "货舱", ship.cargoUsed().stripTrailingZeros().toPlainString()
                        + "/" + ship.cargoCapacity().stripTrailingZeros().toPlainString(),
                Style.PANEL_MUTED, Style.PANEL);
        Draw.kv(buffer, side, row++, "航程", ship.voyage(), Style.PANEL_MUTED, Style.PANEL);
        if (row + 1 < side.height()) {
            Draw.line(buffer, side, row + 1, "select up/down 切换  inspect 详情",
                    Style.PANEL_MUTED);
        }
    }

    public static String detail(Snapshot snapshot, int selected) {
        List<Snapshot.ShipView> ships = snapshot.ships();
        if (ships.isEmpty()) {
            return "当前没有舰船。";
        }
        Snapshot.ShipView ship = ships.get(clamp(selected, ships.size()));
        return ship.name() + "  (" + ship.type() + ")\n"
                + "编号：" + ship.id() + "\n"
                + "位置：" + ship.location() + "\n"
                + "耐久：" + ship.hull() + "/" + ship.maxHull() + "\n"
                + "船员上限：" + ship.crewCapacity() + "\n"
                + "货舱：" + ship.cargoUsed().stripTrailingZeros().toPlainString()
                + "/" + ship.cargoCapacity().stripTrailingZeros().toPlainString() + "\n"
                + "航程：" + ship.voyage();
    }

    private static int clamp(int selected, int size) {
        return size <= 0 ? 0 : Math.max(0, Math.min(size - 1, selected));
    }
}
