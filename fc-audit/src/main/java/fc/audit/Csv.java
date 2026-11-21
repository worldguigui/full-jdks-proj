package fc.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * 最小 RFC 4180 CSV 解析：支持引号、逗号与双引号转义。
 *
 * <p>阶段一清单含空字段，但不应假设永远没有逗号/引号；用正确的行解析器
 * 可避免扩展列（如未覆盖原因）中的逗号把报告读歪。</p>
 */
public final class Csv {

    private Csv() {
    }

    /** 解析一行 CSV；不处理跨行引号字段（基准清单没有）。 */
    public static List<String> parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("CSV 引号未闭合: " + line);
        }
        fields.add(field.toString());
        return fields;
    }

    /** 生成一个 CSV 字段（需要时加引号）。 */
    public static String escape(String value) {
        String s = value == null ? "" : value;
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0
                || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
