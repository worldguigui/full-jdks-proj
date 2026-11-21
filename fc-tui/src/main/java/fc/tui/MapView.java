package fc.tui;

import fc.world.ResourceKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 星域导航：二维字符投影 + 地点详情。
 *
 * <p>只投影已知地点（知识状态非 UNKNOWN）；位置由地点 id 排序后按网格确定性分配，
 * 不尝试复刻 3D 星图。渲染只读 {@link Snapshot}，不执行寻路或探索。</p>
 */
public final class MapView {

    private MapView() {
    }

    /** 可选择的已知地点（按快照顺序）。 */
    public static List<Snapshot.Place> known(Snapshot snapshot) {
        List<Snapshot.Place> out = new ArrayList<>();
        for (Snapshot.Place place : snapshot.places()) {
            if (place.knowledge() != fc.world.KnowledgeState.UNKNOWN) {
                out.add(place);
            }
        }
        return out;
    }

    public static int count(Snapshot snapshot) {
        return known(snapshot).size();
    }

    public static void render(ScreenBuffer buffer, Rect body, Snapshot snapshot, int selected,
                              CharSet chars) {
        int leftWidth = Math.max(36, body.width() * 64 / 100);
        Rect[] columns = body.splitColumns(leftWidth);
        Rect mapArea = Draw.panel(buffer, columns[0], "[01] 战略星图 / SECTOR", chars);
        drawMap(buffer, mapArea, snapshot, selected, chars);

        Rect side = Draw.panel(buffer, columns[1], "[SELECTED] 地点详情", chars);
        List<Snapshot.Place> places = known(snapshot);
        int index = clamp(selected, places.size());
        if (places.isEmpty()) {
            Draw.line(buffer, side, 0, "尚无已知地点。", Style.PANEL_MUTED);
            Draw.line(buffer, side, 2, "用 survey / sail 扩展星图。", Style.PANEL_MUTED);
            return;
        }
        Snapshot.Place place = places.get(index);
        int row = 0;
        Draw.line(buffer, side, row++, place.name(), Style.PANEL_ACCENT);
        Draw.kv(buffer, side, row++, "类型", place.kind(), Style.PANEL_MUTED, Style.PANEL);
        Draw.kv(buffer, side, row++, "知识", place.knowledge().displayName(),
                Style.PANEL_MUTED, Style.PANEL);
        Draw.kv(buffer, side, row++, "归属", place.owner(),
                Style.PANEL_MUTED, Style.PANEL);
        Draw.kv(buffer, side, row++, "编号", "L" + place.id(),
                Style.PANEL_MUTED, Style.PANEL);
        if (!place.stock().isEmpty()) {
            Draw.line(buffer, side, row++, "库存", Style.PANEL_MUTED);
            for (Map.Entry<ResourceKind, java.math.BigDecimal> entry : place.stock().entrySet()) {
                if (row >= side.height()) {
                    break;
                }
                Draw.kv(buffer, side, row++, "  " + entry.getKey().displayName(),
                        entry.getValue().stripTrailingZeros().toPlainString(),
                        Style.PANEL_MUTED, Style.PANEL_OK);
            }
        }
        if (row < side.height()) {
            Draw.line(buffer, side, row++, "航线 " + place.links().size() + " 条",
                    Style.PANEL_MUTED);
        }
        if (row < side.height()) {
            Draw.line(buffer, side, row, "select up/down 切换  inspect 详情  back 返回",
                    Style.PANEL_MUTED);
        }
    }

    /** 在给定区域内绘制地图（含图例），供总览与导航页共用。 */
    public static void drawMap(ScreenBuffer buffer, Rect area, Snapshot snapshot, int selected,
                               CharSet chars) {
        List<Snapshot.Place> places = known(snapshot);
        if (places.isEmpty() || area.width() < 12 || area.height() < 4) {
            Draw.line(buffer, area, 0, "星图暂无可显示地点。", Style.PANEL_MUTED);
            return;
        }
        int plotHeight = area.height() - 1;
        int columns = Math.max(1, Math.min(6, (int) Math.ceil(Math.sqrt(places.size()))));
        int rows = Math.max(1, (places.size() + columns - 1) / columns);
        int cellWidth = Math.max(3, area.width() / columns);
        int cellHeight = Math.max(2, plotHeight / rows);
        int[] xs = new int[places.size()];
        int[] ys = new int[places.size()];
        Map<String, Integer> byName = new HashMap<>();
        for (int i = 0; i < places.size(); i++) {
            int col = i % columns;
            int row = i / columns;
            xs[i] = Math.min(area.right() - 2,
                    area.x() + col * cellWidth + cellWidth / 2);
            ys[i] = Math.min(area.bottom() - 2,
                    area.y() + row * cellHeight + cellHeight / 2);
            byName.put(places.get(i).name(), i);
        }
        // 航线先画，节点后画，避免线覆盖符号。
        for (int i = 0; i < places.size(); i++) {
            for (String linkName : places.get(i).links()) {
                Integer target = byName.get(linkName);
                if (target == null || target <= i) {
                    continue;
                }
                drawRoute(buffer, xs[i], ys[i], xs[target], ys[target], chars);
            }
        }
        int index = clamp(selected, places.size());
        for (int i = 0; i < places.size(); i++) {
            Snapshot.Place place = places.get(i);
            boolean chosen = i == index;
            String symbol = chosen ? chars.star() : chars.known();
            Style style = chosen ? Style.SELECTED
                    : place.playerOwned() ? Style.PANEL_OK : Style.PANEL;
            buffer.putClipped(xs[i], ys[i], symbol, style, 2);
            if (cellHeight >= 3 && cellWidth >= 6) {
                String label = TextWidth.truncate(place.name(), cellWidth - 1);
                int labelX = Math.max(area.x(), Math.min(area.right() - TextWidth.width(label),
                        xs[i] - TextWidth.width(label) / 2));
                buffer.putClipped(labelX, ys[i] + 1, label,
                        chosen ? Style.PANEL_ACCENT : Style.PANEL_MUTED, cellWidth);
            }
        }
        int legendRow = area.height() - 1;
        String legend = chars.star() + "选中 " + chars.known() + "已知 "
                + chars.unknown() + "未知 " + snapshot.unknownPlaces() + " 处"
                + "｜航线 " + snapshot.places().stream()
                        .mapToInt(p -> p.links().size()).sum() / 2 + " 条";
        Draw.line(buffer, new Rect(area.x(), area.y() + legendRow, area.width(), 1),
                0, legend, Style.PANEL_MUTED);
    }

    /** 选择项详情（弹窗用）。 */
    public static String detail(Snapshot snapshot, int selected) {
        List<Snapshot.Place> places = known(snapshot);
        if (places.isEmpty()) {
            return "尚无已知地点。";
        }
        Snapshot.Place place = places.get(clamp(selected, places.size()));
        StringBuilder out = new StringBuilder();
        out.append(place.name()).append("  (").append(place.kind()).append(")\n")
                .append("编号：L").append(place.id()).append('\n')
                .append("知识：").append(place.knowledge().displayName()).append('\n')
                .append("归属：").append(place.owner()).append('\n')
                .append("玩家属地：").append(place.playerOwned() ? "是" : "否").append('\n');
        if (!place.stock().isEmpty()) {
            out.append("库存：");
            List<String> parts = new ArrayList<>();
            for (Map.Entry<ResourceKind, java.math.BigDecimal> entry : place.stock().entrySet()) {
                parts.add(entry.getKey().displayName() + " "
                        + entry.getValue().stripTrailingZeros().toPlainString());
            }
            out.append(String.join(" / ", parts)).append('\n');
        }
        out.append("航线：").append(place.links().isEmpty()
                ? "无" : String.join("、", place.links()));
        return out.toString();
    }

    private static void drawRoute(ScreenBuffer buffer, int x0, int y0, int x1, int y1,
                                  CharSet chars) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int error = dx - dy;
        int x = x0;
        int y = y0;
        String horizontal = chars.routeHorizontal();
        String vertical = chars.routeVertical();
        while (x != x1 || y != y1) {
            int e2 = 2 * error;
            if (e2 > -dy) {
                error -= dy;
                x += sx;
            }
            if (e2 < dx) {
                error += dx;
                y += sy;
            }
            if (x == x1 && y == y1) {
                break;
            }
            String ch = dx >= dy ? horizontal : vertical;
            buffer.putClipped(x, y, ch, Style.PANEL_MUTED, 1);
        }
    }

    private static int clamp(int selected, int size) {
        if (size <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(size - 1, selected));
    }
}
