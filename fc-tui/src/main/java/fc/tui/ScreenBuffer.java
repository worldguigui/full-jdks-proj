package fc.tui;

/**
 * 终端画布：固定尺寸的字符 + 样式双缓冲。宽字符占两列，第二列标记为续接；
 * 差分渲染器据此跳过重复输出。
 */
public final class ScreenBuffer {

    private final int width;
    private final int height;
    private final int[] codePoints;
    private final Style[] styles;
    private final boolean[] continuation;

    public ScreenBuffer(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("画布尺寸必须为正：" + width + "×" + height);
        }
        this.width = width;
        this.height = height;
        this.codePoints = new int[width * height];
        this.styles = new Style[width * height];
        this.continuation = new boolean[width * height];
        clear(Style.DEFAULT);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public void clear(Style style) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int at = index(x, y);
                codePoints[at] = ' ';
                styles[at] = style;
                continuation[at] = false;
            }
        }
    }

    /** 在 (x,y) 写入文本；超出右边界的部分被裁剪。 */
    public void put(int x, int y, String text, Style style) {
        putClipped(x, y, text, style, width - x);
    }

    /** 在 (x,y) 写入文本，最多占 maxWidth 列。 */
    public void putClipped(int x, int y, String text, Style style, int maxWidth) {
        if (text == null || y < 0 || y >= height || x >= width || maxWidth <= 0) {
            return;
        }
        int cx = x;
        int limit = Math.min(width, x + maxWidth);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            // 数据（地点名/Mod 文本）不得注入终端控制序列。
            if (cp < 0x20 || cp == 0x7F || (cp >= 0x80 && cp <= 0x9F)) {
                cp = '?';
            }
            int w = TextWidth.of(cp);
            if (w == 0) {
                continue;
            }
            if (cx + w > limit) {
                break;
            }
            if (cx >= 0) {
                writeCell(cx, y, cp, style, w);
            }
            cx += w;
        }
    }

    /** 用空格铺满区域（含背景色）。 */
    public void fill(Rect rect, Style style) {
        for (int y = rect.y(); y < rect.bottom() && y < height; y++) {
            for (int x = rect.x(); x < rect.right() && x < width; x++) {
                if (x >= 0 && y >= 0) {
                    writeCell(x, y, ' ', style, 1);
                }
            }
        }
    }

    /** 画单线边框，可选标题（顶部居中偏左）。 */
    public void box(Rect rect, String title, Style borderStyle, Style titleStyle, CharSet cs) {
        if (rect.width() < 2 || rect.height() < 2) {
            return;
        }
        int right = rect.right() - 1;
        int bottom = rect.bottom() - 1;
        put(rect.x(), rect.y(), cs.topLeft(), borderStyle);
        put(right, rect.y(), cs.topRight(), borderStyle);
        put(rect.x(), bottom, cs.bottomLeft(), borderStyle);
        put(right, bottom, cs.bottomRight(), borderStyle);
        horizontal(rect.y(), rect.x() + 1, rect.width() - 2, cs.horizontal(), borderStyle);
        horizontal(bottom, rect.x() + 1, rect.width() - 2, cs.horizontal(), borderStyle);
        vertical(rect.x(), rect.y() + 1, rect.height() - 2, cs.vertical(), borderStyle);
        vertical(right, rect.y() + 1, rect.height() - 2, cs.vertical(), borderStyle);
        if (title != null && !title.isBlank() && rect.width() > 6) {
            String text = TextWidth.truncate(title, rect.width() - 4);
            put(rect.x() + 2, rect.y(), " " + text + " ", titleStyle);
        }
    }

    public void horizontal(int y, int x, int length, String ch, Style style) {
        if (y < 0 || y >= height) {
            return;
        }
        for (int i = 0; i < length; i++) {
            int cx = x + i;
            if (cx >= 0 && cx < width) {
                writeCell(cx, y, ch.codePointAt(0), style, 1);
            }
        }
    }

    public void vertical(int x, int y, int length, String ch, Style style) {
        if (x < 0 || x >= width) {
            return;
        }
        int cp = ch.codePointAt(0);
        for (int i = 0; i < length; i++) {
            int cy = y + i;
            if (cy >= 0 && cy < height) {
                writeCell(x, cy, cp, style, 1);
            }
        }
    }

    /** 进度条（含方括号）；fraction 被限制在 0..1。 */
    public void bar(int x, int y, int width, double fraction, Style fullStyle,
                    Style emptyStyle, CharSet cs) {
        if (width < 2) {
            return;
        }
        double clamped = Math.max(0.0, Math.min(1.0, fraction));
        int inner = width - 2;
        int filled = (int) Math.round(inner * clamped);
        put(x, y, "[", emptyStyle);
        for (int i = 0; i < inner; i++) {
            String ch = i < filled ? cs.barFull() : cs.barEmpty();
            put(x + 1 + i, y, ch, i < filled ? fullStyle : emptyStyle);
        }
        put(x + width - 1, y, "]", emptyStyle);
    }

    /**
     * 整行文本（用于测试与日志）。宽字符本身已占两列，续接格不再输出空格，
     * 否则 {@link TextWidth#width(String)} 会把每个汉字算成三列。
     */
    public String row(int y) {
        StringBuilder out = new StringBuilder(width);
        for (int x = 0; x < width; x++) {
            int at = index(x, y);
            if (!continuation[at]) {
                out.appendCodePoint(codePoints[at]);
            }
        }
        return out.toString();
    }

    public int codePoint(int x, int y) {
        return codePoints[index(x, y)];
    }

    public Style style(int x, int y) {
        return styles[index(x, y)];
    }

    public boolean continuation(int x, int y) {
        return continuation[index(x, y)];
    }

    private void writeCell(int x, int y, int cp, Style style, int widthOfChar) {
        int at = index(x, y);
        codePoints[at] = cp;
        styles[at] = style;
        continuation[at] = false;
        if (widthOfChar == 2 && x + 1 < width) {
            int next = index(x + 1, y);
            codePoints[next] = ' ';
            styles[next] = style;
            continuation[next] = true;
        }
    }

    private int index(int x, int y) {
        return y * width + x;
    }
}
