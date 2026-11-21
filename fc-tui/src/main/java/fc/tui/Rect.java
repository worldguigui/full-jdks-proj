package fc.tui;

/** 终端矩形区域（列、行、宽、高），坐标从 0 开始。 */
public record Rect(int x, int y, int width, int height) {

    public Rect {
        if (x < 0 || y < 0 || width < 0 || height < 0) {
            throw new IllegalArgumentException("区域坐标/尺寸不能为负");
        }
    }

    public int right() {
        return x + width;
    }

    public int bottom() {
        return y + height;
    }

    public boolean contains(int column, int row) {
        return column >= x && column < right() && row >= y && row < bottom();
    }

    public Rect inset(int left, int top, int right, int bottom) {
        int w = Math.max(0, width - left - right);
        int h = Math.max(0, height - top - bottom);
        return new Rect(x + left, y + top, w, h);
    }

    public Rect inset(int dx, int dy) {
        return inset(dx, dy, dx, dy);
    }

    /** 左右分割：左侧取 leftWidth 列，右侧取剩余。 */
    public Rect[] splitColumns(int leftWidth) {
        int left = Math.max(0, Math.min(width, leftWidth));
        return new Rect[] {
                new Rect(x, y, left, height),
                new Rect(x + left, y, width - left, height)
        };
    }

    /** 上下分割：上方取 topHeight 行。 */
    public Rect[] splitRows(int topHeight) {
        int top = Math.max(0, Math.min(height, topHeight));
        return new Rect[] {
                new Rect(x, y, width, top),
                new Rect(x, y + top, width, height - top)
        };
    }

    /** 按比例取宽（用于主面板/侧栏）。 */
    public Rect column(double fraction) {
        int w = (int) Math.round(width * Math.max(0.0, Math.min(1.0, fraction)));
        return new Rect(x, y, w, height);
    }
}
