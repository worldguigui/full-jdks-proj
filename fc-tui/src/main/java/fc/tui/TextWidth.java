package fc.tui;

/**
 * 终端显示宽度计算：中文/全角/emoji 占两列，组合字符占零列，其余占一列。
 *
 * <p>布局、截断与差分刷新都必须使用本类，不能使用 {@link String#length()}。
 * 宽度表覆盖常见 CJK、全角标点、emoji 与组合附加符；无法识别的码位按一列处理，
 * 这是保守且可预测的降级。</p>
 */
public final class TextWidth {

    private TextWidth() {
    }

    /** 单个码位的显示宽度（0、1 或 2）。 */
    public static int of(int codePoint) {
        if (codePoint == 0) {
            return 0;
        }
        if (isZeroWidth(codePoint)) {
            return 0;
        }
        return isWide(codePoint) ? 2 : 1;
    }

    /** 字符串的显示宽度。 */
    public static int width(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            sum += of(cp);
            i += Character.charCount(cp);
        }
        return sum;
    }

    /** 按显示宽度截断；放不下时用 {@code ..} 收尾（总宽度不超过 max）。 */
    public static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        if (max <= 0) {
            return "";
        }
        if (width(text) <= max) {
            return text;
        }
        String ellipsis = max >= 2 ? ".." : ".";
        int budget = max - width(ellipsis);
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int w = of(cp);
            if (used + w > budget) {
                break;
            }
            out.appendCodePoint(cp);
            used += w;
            i += Character.charCount(cp);
        }
        return out.append(ellipsis).toString();
    }

    /** 左对齐补空格到给定显示宽度；已超宽时先截断。 */
    public static String padRight(String text, int width) {
        String clipped = truncate(text == null ? "" : text, width);
        return clipped + " ".repeat(Math.max(0, width - width(clipped)));
    }

    /** 右对齐补空格到给定显示宽度。 */
    public static String padLeft(String text, int width) {
        String clipped = truncate(text == null ? "" : text, width);
        return " ".repeat(Math.max(0, width - width(clipped))) + clipped;
    }

    private static boolean isZeroWidth(int cp) {
        return (cp >= 0x0300 && cp <= 0x036F)
                || (cp >= 0x1AB0 && cp <= 0x1AFF)
                || (cp >= 0x1DC0 && cp <= 0x1DFF)
                || (cp >= 0x200B && cp <= 0x200F)
                || (cp >= 0x20D0 && cp <= 0x20FF)
                || (cp >= 0xFE00 && cp <= 0xFE0F)
                || (cp >= 0xFE20 && cp <= 0xFE2F)
                || (cp >= 0xE0100 && cp <= 0xE01EF)
                || (cp >= 0x1F3FB && cp <= 0x1F3FF);
    }

    private static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)      // 谚文字母
                || (cp >= 0x2E80 && cp <= 0x303E)  // CJK 部首/标点
                || (cp >= 0x3041 && cp <= 0x33FF)  // 假名、CJK 符号
                || (cp >= 0x3400 && cp <= 0x4DBF)  // CJK 扩展 A
                || (cp >= 0x4E00 && cp <= 0x9FFF)  // CJK 统一表意
                || (cp >= 0xA000 && cp <= 0xA4CF)  // 彝文
                || (cp >= 0xAC00 && cp <= 0xD7A3)  // 谚文音节
                || (cp >= 0xF900 && cp <= 0xFAFF)  // CJK 兼容
                || (cp >= 0xFE10 && cp <= 0xFE19)  // 竖排标点
                || (cp >= 0xFE30 && cp <= 0xFE6F)  // CJK 兼容形式
                || (cp >= 0xFF00 && cp <= 0xFF60)  // 全角 ASCII
                || (cp >= 0xFFE0 && cp <= 0xFFE6)  // 全角符号
                || (cp >= 0x1F300 && cp <= 0x1FAFF) // emoji
                || (cp >= 0x20000 && cp <= 0x3FFFD); // CJK 扩展 B+
    }
}
