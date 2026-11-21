package fc.tui;

/** ANSI/VT 控制序列。所有序列集中在此，页面代码不直接拼接转义串。 */
public final class Ansi {

    /** CSI 前缀：ESC + '['。 */
    public static final String ESC = "\u001B[";
    public static final String RESET = ESC + "0m";
    public static final String CLEAR = ESC + "2J" + ESC + "H";
    public static final String HOME = ESC + "H";
    public static final String ALT_SCREEN_ON = ESC + "?1049h";
    public static final String ALT_SCREEN_OFF = ESC + "?1049l";
    public static final String CURSOR_SHOW = ESC + "?25h";
    public static final String CURSOR_HIDE = ESC + "?25l";
    public static final String WRAP_OFF = ESC + "?7l";
    public static final String WRAP_ON = ESC + "?7h";

    private Ansi() {
    }

    /** 1 基坐标定位光标。 */
    public static String cursor(int row, int column) {
        return ESC + row + ";" + column + "H";
    }

    /** 光标上移 n 行（n &gt; 0）。 */
    public static String up(int rows) {
        return rows <= 0 ? "" : ESC + rows + "A";
    }

    /** 24 位真彩 SGR；不支持颜色时只保留粗体。 */
    public static String sgr(Style style, boolean color) {
        StringBuilder out = new StringBuilder(ESC);
        out.append("0");
        if (style.isBold()) {
            out.append(";1");
        }
        if (color) {
            out.append(";38;2;").append(style.foreground().red())
                    .append(';').append(style.foreground().green())
                    .append(';').append(style.foreground().blue())
                    .append(";48;2;").append(style.background().red())
                    .append(';').append(style.background().green())
                    .append(';').append(style.background().blue());
        }
        return out.append('m').toString();
    }
}
