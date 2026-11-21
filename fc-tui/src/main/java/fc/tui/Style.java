package fc.tui;

/**
 * 终端调色板与文本样式：深空蓝黑背景、冷青强调、琥珀警告。
 * 颜色用 24 位 RGB；不支持真彩的终端由 {@link Ansi} 退化为粗体/默认色。
 */
public final class Style {

    /** 设计规范中的八种语义色。 */
    public enum Color {
        BACKGROUND(0x080F19),
        PANEL(0x142536),
        ACCENT(0x55D6D2),
        WARN(0xE5AD56),
        TEXT(0xD9E4EE),
        MUTED(0x8195A9),
        DANGER(0xD66B68),
        OK(0x70C994);

        private final int rgb;

        Color(int rgb) {
            this.rgb = rgb;
        }

        public int rgb() {
            return rgb;
        }

        public int red() {
            return (rgb >> 16) & 0xFF;
        }

        public int green() {
            return (rgb >> 8) & 0xFF;
        }

        public int blue() {
            return rgb & 0xFF;
        }
    }

    public static final Style DEFAULT = new Style(Color.TEXT, Color.BACKGROUND, false);
    public static final Style ACCENT = new Style(Color.ACCENT, Color.BACKGROUND, true);
    public static final Style MUTED = new Style(Color.MUTED, Color.BACKGROUND, false);
    public static final Style WARN = new Style(Color.WARN, Color.BACKGROUND, true);
    public static final Style DANGER = new Style(Color.DANGER, Color.BACKGROUND, true);
    public static final Style OK = new Style(Color.OK, Color.BACKGROUND, false);
    public static final Style PANEL = new Style(Color.TEXT, Color.PANEL, false);
    public static final Style PANEL_MUTED = new Style(Color.MUTED, Color.PANEL, false);
    public static final Style PANEL_ACCENT = new Style(Color.ACCENT, Color.PANEL, true);
    public static final Style PANEL_WARN = new Style(Color.WARN, Color.PANEL, true);
    public static final Style PANEL_OK = new Style(Color.OK, Color.PANEL, false);
    public static final Style HEADER = new Style(Color.BACKGROUND, Color.ACCENT, true);
    public static final Style SELECTED = new Style(Color.BACKGROUND, Color.ACCENT, true);

    private final Color foreground;
    private final Color background;
    private final boolean bold;

    public Style(Color foreground, Color background, boolean bold) {
        this.foreground = foreground == null ? Color.TEXT : foreground;
        this.background = background == null ? Color.BACKGROUND : background;
        this.bold = bold;
    }

    public static Style of(Color foreground) {
        return new Style(foreground, Color.BACKGROUND, false);
    }

    public static Style of(Color foreground, Color background) {
        return new Style(foreground, background, false);
    }

    public Style bold() {
        return bold ? this : new Style(foreground, background, true);
    }

    public Color foreground() {
        return foreground;
    }

    public Color background() {
        return background;
    }

    public boolean isBold() {
        return bold;
    }
}
