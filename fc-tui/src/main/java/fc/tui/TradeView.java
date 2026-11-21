package fc.tui;

import fc.world.ResourceKind;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 贸易中心：玩家资源、市场报价文本与交易提示。 */
public final class TradeView {

    private TradeView() {
    }

    public static void render(ScreenBuffer buffer, Rect body, Snapshot snapshot, CharSet chars) {
        Rect[] rows = body.splitRows(Math.max(5, Math.min(9, body.height() / 3)));
        Rect stock = Draw.panel(buffer, rows[0], "[05] 组织库存 / TRADE", chars);
        Rect market = Draw.panel(buffer, rows[1], "市场行情", chars);

        Draw.line(buffer, stock, 0, "信用点 " + snapshot.credits(), Style.PANEL_ACCENT);
        int row = 1;
        int column = 0;
        int perColumn = 3;
        for (Map.Entry<ResourceKind, BigDecimal> entry : snapshot.playerResources().entrySet()) {
            if (entry.getValue().signum() == 0) {
                continue;
            }
            int atRow = 1 + (column % perColumn);
            int atColumn = (column / perColumn) * Math.max(16, stock.width() / 4);
            if (atColumn >= stock.width()) {
                break;
            }
            buffer.putClipped(stock.x() + atColumn, stock.y() + atRow,
                    TextWidth.padRight(entry.getKey().displayName() + " "
                            + entry.getValue().stripTrailingZeros().toPlainString(),
                            Math.max(12, stock.width() / 4)),
                    Style.PANEL_OK, Math.max(12, stock.width() / 4));
            column++;
        }
        Draw.line(buffer, stock, stock.height() - 1,
                "market <舰船> 查看报价；trade buy/sell <货物> <数量>",
                Style.PANEL_MUTED);

        List<String> lines = Draw.wrap(snapshot.marketReport(), market.width());
        for (int i = 0; i < lines.size() && i < market.height(); i++) {
            Draw.line(buffer, market, i, lines.get(i), Style.PANEL_MUTED);
        }
    }
}
