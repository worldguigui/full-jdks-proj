package fc.tui;

import java.util.ArrayList;
import java.util.List;

/** 舰桥态势总览：星图、舰队、情报三栏。 */
public final class BridgeView {

    private BridgeView() {
    }

    public static void render(ScreenBuffer buffer, Rect body, Snapshot snapshot, CharSet chars) {
        if (body.width() < 100) {
            // 窄终端：左星图，右栏上下堆叠舰队与情报，避免三个窄栏标题被截断。
            Rect[] columns = body.splitColumns(Math.max(36, body.width() * 62 / 100));
            Rect map = columns[0];
            Rect[] right = columns[1].splitRows(columns[1].height() / 2);
            Rect mapInner = Draw.panel(buffer, map, "[01] 星图 / MAP", chars);
            MapView.drawMap(buffer, mapInner, snapshot, 0, chars);
            Rect fleetInner = Draw.panel(buffer, right[0], "[02] 舰队 / FLEET", chars);
            drawFleet(buffer, fleetInner, snapshot, chars);
            Rect intelInner = Draw.panel(buffer, right[1], "[03] 情报 / INTEL", chars);
            drawIntel(buffer, intelInner, snapshot, chars);
            return;
        }
        int leftWidth = Math.max(30, body.width() * 55 / 100);
        int middleWidth = Math.max(22, body.width() * 24 / 100);
        int middleX = body.x() + leftWidth;
        int rightX = Math.min(body.right(), middleX + middleWidth);
        Rect map = new Rect(body.x(), body.y(), leftWidth, body.height());
        Rect fleet = new Rect(middleX, body.y(), Math.max(0, rightX - middleX), body.height());
        Rect intel = new Rect(rightX, body.y(), Math.max(0, body.right() - rightX),
                body.height());

        Rect mapInner = Draw.panel(buffer, map, "[01] 战略星图 / STRATEGIC MAP", chars);
        MapView.drawMap(buffer, mapInner, snapshot, 0, chars);

        Rect fleetInner = Draw.panel(buffer, fleet, "[02] 舰队状态 / FLEET", chars);
        drawFleet(buffer, fleetInner, snapshot, chars);

        Rect intelInner = Draw.panel(buffer, intel, "[03] 情报 / INTELLIGENCE", chars);
        drawIntel(buffer, intelInner, snapshot, chars);
    }

    private static void drawFleet(ScreenBuffer buffer, Rect area, Snapshot snapshot,
                                  CharSet chars) {
        if (snapshot.ships().isEmpty()) {
            Draw.line(buffer, area, 0, "无舰船", Style.PANEL_MUTED);
            return;
        }
        int row = 0;
        for (Snapshot.ShipView ship : snapshot.ships()) {
            if (row + 2 >= area.height()) {
                break;
            }
            Draw.line(buffer, area, row++, TextWidth.truncate(ship.name(), area.width()),
                    Style.PANEL_ACCENT);
            String hull = Draw.percent(ship.hull(), ship.maxHull()) + "%";
            buffer.bar(area.x(), area.y() + row++, Math.max(6, Math.min(12, area.width() - 5)),
                    ship.hull() / (double) Math.max(1, ship.maxHull()),
                    hullPercentStyle(ship), Style.PANEL_MUTED, chars);
            Draw.line(buffer, area, row++, TextWidth.truncate(
                    ship.hull() + "/" + ship.maxHull() + "  " + ship.voyage(), area.width()),
                    Style.PANEL_MUTED);
            row++;
            if (row >= area.height()) {
                break;
            }
        }
    }

    private static Style hullPercentStyle(Snapshot.ShipView ship) {
        if (ship.hull() * 2 < ship.maxHull()) {
            return Style.PANEL_WARN;
        }
        return Style.PANEL_OK;
    }

    private static void drawIntel(ScreenBuffer buffer, Rect area, Snapshot snapshot,
                                  CharSet chars) {
        int row = 0;
        Draw.line(buffer, area, row++, "最近事件", Style.PANEL_MUTED);
        List<Snapshot.EventRow> recent = new ArrayList<>(snapshot.events());
        int from = Math.max(0, recent.size() - 5);
        for (int i = from; i < recent.size() && row < area.height() - 2; i++) {
            Snapshot.EventRow event = recent.get(i);
            Draw.line(buffer, area, row++, "D" + event.day() + " "
                    + TextWidth.truncate(event.summary(), area.width() - 5),
                    Style.PANEL_MUTED);
        }
        if (recent.isEmpty()) {
            Draw.line(buffer, area, row++, "暂无事件", Style.PANEL_MUTED);
        }
        row++;
        Draw.line(buffer, area, row++, "待办 " + snapshot.decisions().size() + " 项",
                snapshot.decisions().isEmpty() ? Style.PANEL_OK : Style.PANEL_WARN);
        for (Snapshot.DecisionView decision : snapshot.decisions()) {
            if (row >= area.height()) {
                break;
            }
            Draw.line(buffer, area, row++, chars.warn() + " [" + decision.id() + "] "
                    + TextWidth.truncate(decision.title(), Math.max(0, area.width() - 8)),
                    Style.PANEL_WARN);
        }
        if (!snapshot.decisions().isEmpty() && row < area.height()) {
            Draw.line(buffer, area, row, "输入 decisions / decide 处理",
                    Style.PANEL_MUTED);
        }
    }
}
