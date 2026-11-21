package fc.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 冻结基准读取器：把 {@code coverage-baseline.csv} 读成不可变结构。
 *
 * <p>本类只读文件，绝不修改阶段一冻结数据。</p>
 */
public record Baseline(List<ApiType> all, List<ApiType> denominator) {

    public Baseline {
        all = List.copyOf(all);
        denominator = List.copyOf(denominator);
    }

    public static Baseline read(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IOException("基准清单为空: " + csv);
        }
        List<String> header = Csv.parseLine(lines.get(0));
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            index.putIfAbsent(header.get(i).strip(), i);
        }
        for (String required : List.of("module", "package", "type_binary", "type_display",
                "kind", "coverage_scope", "preview")) {
            if (!index.containsKey(required)) {
                throw new IOException("基准清单缺少列 " + required + ": " + csv);
            }
        }
        List<ApiType> all = new ArrayList<>(4000);
        List<ApiType> denominator = new ArrayList<>(3900);
        for (int n = 1; n < lines.size(); n++) {
            String line = lines.get(n);
            if (line.isBlank()) {
                continue;
            }
            List<String> f = Csv.parseLine(line);
            ApiType type = new ApiType(
                    field(f, index, "module"),
                    field(f, index, "package"),
                    field(f, index, "type_binary"),
                    field(f, index, "type_display"),
                    field(f, index, "kind"),
                    field(f, index, "coverage_scope"),
                    "yes".equalsIgnoreCase(field(f, index, "preview")));
            all.add(type);
            if (type.inDenominator()) {
                denominator.add(type);
            }
        }
        if (denominator.isEmpty()) {
            throw new IOException("基准清单没有 IN_DENOMINATOR 行: " + csv);
        }
        return new Baseline(all, denominator);
    }

    private static String field(List<String> fields, Map<String, Integer> index, String name)
            throws IOException {
        int i = index.get(name);
        if (i >= fields.size()) {
            throw new IOException("基准行列数不足（缺 " + name + "）");
        }
        return fields.get(i).strip();
    }

    /** 二进制类型名 → 行。 */
    public Map<String, ApiType> byBinary() {
        Map<String, ApiType> map = new LinkedHashMap<>();
        for (ApiType type : all) {
            map.put(type.binary(), type);
        }
        return map;
    }

    public TreeSet<String> packages() {
        TreeSet<String> set = new TreeSet<>();
        for (ApiType type : denominator) {
            set.add(type.packageName());
        }
        return set;
    }

    public TreeSet<String> modules() {
        TreeSet<String> set = new TreeSet<>();
        for (ApiType type : denominator) {
            set.add(type.module());
        }
        return set;
    }
}
