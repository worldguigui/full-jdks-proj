package fc.tui;

import java.util.ArrayList;
import java.util.List;

/** 页面共用的绘制辅助：面板、行文本、换行与列表高亮。 */
public final class Draw {

    private Draw() {
    }

    /** 填充面板底色并画边框/标题。 */
    public static Rect panel(ScreenBuffer buffer, Rect rect, String title, CharSet chars) {
        buffer.fill(rect, Style.PANEL);
        buffer.box(rect, title, Style.PANEL_MUTED, Style.PANEL_ACCENT, chars);
        return rect.inset(1, 1);
    }

    /** 在面板内写一行；超宽时用 {@code ..} 收尾。 */
    public static void line(ScreenBuffer buffer, Rect area, int row, String text, Style style) {
        if (row < 0 || row >= area.height()) {
            return;
        }
        buffer.putClipped(area.x(), area.y() + row,
                TextWidth.truncate(text, area.width()), style, area.width());
    }

    /** 行首标签 + 值，值会被裁剪到剩余宽度。 */
    public static void kv(ScreenBuffer buffer, Rect area, int row, String label, String value,
                          Style labelStyle, Style valueStyle) {
        if (row < 0 || row >= area.height()) {
            return;
        }
        String left = TextWidth.padRight(label, Math.min(12, area.width()));
        buffer.putClipped(area.x(), area.y() + row, left, labelStyle, area.width());
        int used = Math.min(12, area.width());
        int remaining = Math.max(0, area.width() - used);
        buffer.putClipped(area.x() + used, area.y() + row,
                TextWidth.truncate(value, remaining), valueStyle, remaining);
    }

    /** 可选中列表行：选中时整行反显。 */
    public static void row(ScreenBuffer buffer, Rect area, int row, String text, boolean selected,
                           Style normal, CharSet chars) {
        if (row < 0 || row >= area.height()) {
            return;
        }
        int y = area.y() + row;
        if (selected) {
            buffer.fill(new Rect(area.x(), y, area.width(), 1), Style.SELECTED);
            buffer.putClipped(area.x(), y, TextWidth.padRight(text, area.width()),
                    Style.SELECTED, area.width());
        } else {
            buffer.putClipped(area.x(), y, TextWidth.truncate(text, area.width()),
                    normal, area.width());
        }
    }

    /** 面板标题用的可见范围，如 {@code "  1-20/57"}；无需滚动时为空。 */
    public static String range(int first, int visible, int total) {
        if (total <= 0 || visible <= 0) {
            return "";
        }
        int start = Math.max(0, Math.min(first, total - 1));
        int end = Math.min(total, start + visible);
        return "  " + (start + 1) + "-" + end + "/" + total;
    }

    /** 取一段行窗口，越界自动裁剪。 */
    public static List<String> slice(List<String> lines, int first, int count) {
        if (lines == null || lines.isEmpty() || count <= 0) {
            return List.of();
        }
        int start = Math.max(0, Math.min(first, lines.size()));
        int end = Math.min(lines.size(), start + count);
        return lines.subList(start, end);
    }

    /** 按显示宽度换行；保留原有空行。 */
    public static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        if (text == null || width <= 0) {
            return out;
        }
        for (String raw : text.replace("\r\n", "\n").split("\n", -1)) {
            if (raw.isBlank()) {
                out.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            int used = 0;
            for (int i = 0; i < raw.length(); ) {
                int cp = raw.codePointAt(i);
                i += Character.charCount(cp);
                int w = TextWidth.of(cp);
                if (used + w > width && !line.isEmpty()) {
                    out.add(line.toString());
                    line.setLength(0);
                    used = 0;
                }
                if (w > width) {
                    continue;
                }
                line.appendCodePoint(cp);
                used += w;
            }
            out.add(line.toString());
        }
        return out;
    }

    /** 从文本中取指定行范围（用于报告滚动）。 */
    public static List<String> lines(String text, int maxLines) {
        List<String> all = text == null ? List.of()
                : List.of(text.replace("\r\n", "\n").split("\n", -1));
        if (all.size() <= maxLines) {
            return all;
        }
        return all.subList(0, Math.max(0, maxLines));
    }

    /** 百分比整数，用于耐久/货舱。 */
    public static int percent(Number value, Number max) {
        if (max == null || max.doubleValue() <= 0) {
            return 0;
        }
        return (int) Math.round(100.0 * value.doubleValue() / max.doubleValue());
    }
}
