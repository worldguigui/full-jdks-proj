package fc.tui;

/**
 * 差分渲染器：把当前画布与上一帧比较，只输出变化的单元格。
 *
 * <p>首帧或尺寸变化时清屏全量绘制；之后按行扫描，遇到连续且同样式的变化单元格
 * 直接追加字符，否则先定位光标再输出 SGR。宽字符的第二列为续接，不会重复输出。
 * 绘制期间关闭自动换行，避免写入右下角触发滚动。</p>
 */
public final class DiffRenderer {

    private final boolean color;
    private ScreenBuffer previous;

    public DiffRenderer(boolean color) {
        this.color = color;
    }

    /** 返回本帧需要写给终端的字符串；无变化时返回空串。 */
    public String render(ScreenBuffer current) {
        if (current == null) {
            throw new IllegalArgumentException("画布不能为空");
        }
        boolean full = previous == null
                || previous.width() != current.width()
                || previous.height() != current.height();
        StringBuilder out = new StringBuilder();
        int lastRow = -1;
        int lastColumn = -1;
        Style lastStyle = null;
        boolean wrote = false;
        if (full) {
            out.append(Ansi.CLEAR);
        }
        out.append(Ansi.WRAP_OFF);
        for (int y = 0; y < current.height(); y++) {
            for (int x = 0; x < current.width(); x++) {
                if (current.continuation(x, y)) {
                    continue;
                }
                int cp = current.codePoint(x, y);
                Style style = current.style(x, y);
                boolean changed = full || previous.codePoint(x, y) != cp
                        || previous.continuation(x, y)
                        || !previous.style(x, y).equals(style);
                if (!changed) {
                    continue;
                }
                if (lastRow != y || lastColumn != x) {
                    out.append(Ansi.cursor(y + 1, x + 1));
                }
                if (!style.equals(lastStyle)) {
                    out.append(Ansi.sgr(style, color));
                    lastStyle = style;
                }
                out.appendCodePoint(cp);
                int w = Math.max(1, TextWidth.of(cp));
                boolean wasWide = !full && !previous.continuation(x, y)
                        && TextWidth.of(previous.codePoint(x, y)) == 2;
                if (wasWide && w == 1 && x + 1 < current.width()) {
                    // 旧的宽字符占两列，覆写为窄字符时补一个空格，避免残留右半格。
                    out.append(' ');
                    lastColumn = x + 2;
                } else {
                    lastColumn = x + w;
                }
                lastRow = y;
                wrote = true;
            }
        }
        if (!wrote && !full) {
            return "";
        }
        out.append(Ansi.WRAP_ON);
        previous = current;
        return out.toString();
    }

    /** 丢弃上一帧，下一次渲染强制全量重绘。 */
    public void reset() {
        previous = null;
    }
}
