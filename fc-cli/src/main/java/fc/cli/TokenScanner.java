package fc.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * 命令行令牌扫描器：按分隔符/引号切分命令参数。
 *
 * <p>支持双引号包裹含空格的参数。M0 骨架提供基础切分，M3 起支持补全与多行粘贴。</p>
 */
public final class TokenScanner {

    private TokenScanner() {
    }

    /** 按空白切分，双引号内容视为一个令牌。 */
    public static List<String> tokenize(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (Character.isWhitespace(c) && !inQuote) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }
}
