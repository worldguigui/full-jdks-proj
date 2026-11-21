package fc.audit;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 覆盖率报告：把 classfile 引用集合与冻结基准逐类型对账。
 *
 * <p>口径严格遵循阶段一：分母 3836 类型 / 176 包 / 20 模块（{@code coverage_scope}
 * = {@code IN_DENOMINATOR} 的行）；38 个 Preview 类型不计入分母。分母中的类型即使
 * 未使用也保留在 CSV 证据中（状态 {@code UNUSED}），因此报告可逐行复核。</p>
 *
 * <p>预览违规单独列出：任何被引用的 Preview 类型或带预览标记的 class 文件都
 * 说明违反了「核心不得使用 Preview API」的约束。</p>
 */
public final class AuditReport {

    private final Baseline baseline;
    private final List<ClassScanner.Result> classes;
    private final Map<String, TreeSet<String>> usedByType = new TreeMap<>();
    private final Map<String, Integer> moduleTotals = new TreeMap<>();
    private final Map<String, Integer> moduleUsed = new TreeMap<>();
    private final Map<String, Integer> packageTotals = new TreeMap<>();
    private final Map<String, Integer> packageUsed = new TreeMap<>();
    private final List<String> previewClasses = new ArrayList<>();
    private final List<ApiType> previewViolations = new ArrayList<>();
    private final Map<String, ApiType> baselineByBinary;

    public AuditReport(Baseline baseline, List<ClassScanner.Result> classes) {
        this.baseline = baseline;
        this.classes = List.copyOf(classes);
        this.baselineByBinary = baseline.byBinary();

        for (ApiType type : baseline.denominator()) {
            moduleTotals.merge(type.module(), 1, Integer::sum);
            packageTotals.merge(type.packageName(), 1, Integer::sum);
        }
        for (ClassScanner.Result result : classes) {
            if (result.preview()) {
                previewClasses.add(result.className());
            }
            for (String ref : result.references()) {
                ApiType type = baselineByBinary.get(ref);
                if (type == null) {
                    continue;
                }
                usedByType.computeIfAbsent(ref, k -> new TreeSet<>()).add(result.className());
                if (type.preview() || !type.inDenominator()) {
                    if (!previewViolations.contains(type)) {
                        previewViolations.add(type);
                    }
                }
            }
        }
        // 覆盖率按「去重后的被引用类型」统计到模块/包。
        for (String ref : usedByType.keySet()) {
            ApiType type = baselineByBinary.get(ref);
            if (type == null || !type.inDenominator()) {
                continue;
            }
            moduleUsed.merge(type.module(), 1, Integer::sum);
            packageUsed.merge(type.packageName(), 1, Integer::sum);
        }
    }

    public int classCount() {
        return classes.size();
    }

    public int matchedTypes() {
        int n = 0;
        for (String ref : usedByType.keySet()) {
            ApiType type = baselineByBinary.get(ref);
            if (type != null && type.inDenominator()) {
                n++;
            }
        }
        return n;
    }

    public int denominatorTypes() {
        return baseline.denominator().size();
    }

    public int matchedPackages() {
        return packageUsed.size();
    }

    public int denominatorPackages() {
        return packageTotals.size();
    }

    public int matchedModules() {
        return moduleUsed.size();
    }

    public int denominatorModules() {
        return moduleTotals.size();
    }

    public List<String> previewClasses() {
        return List.copyOf(previewClasses);
    }

    public List<ApiType> previewViolations() {
        return List.copyOf(previewViolations);
    }

    public boolean clean() {
        return previewClasses.isEmpty() && previewViolations.isEmpty();
    }

    /** 覆盖类型 → 引用它的 class 名（只读）。 */
    public Map<String, TreeSet<String>> usedTypes() {
        Map<String, TreeSet<String>> copy = new TreeMap<>();
        usedByType.forEach((k, v) -> copy.put(k, new TreeSet<>(v)));
        return copy;
    }

    /** 一行摘要，供 CI 日志使用。 */
    public String summary() {
        return String.format(Locale.ROOT,
                "类型 %d/%d（%.1f%%）｜包 %d/%d（%.1f%%）｜模块 %d/%d｜class %d｜预览违规 %d",
                matchedTypes(), denominatorTypes(), percent(matchedTypes(), denominatorTypes()),
                matchedPackages(), denominatorPackages(),
                percent(matchedPackages(), denominatorPackages()),
                matchedModules(), denominatorModules(), classCount(),
                previewClasses.size() + previewViolations.size());
    }

    public void writeMarkdown(Path file) throws IOException {
        createParent(file);
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("# Frontier Chronicle · API 使用审计报告\n\n");
            out.write("> 生成日期：" + LocalDate.now() + " ｜ 数据口径：阶段一冻结清单"
                    + "（分母 3836 类型 / 176 包 / 20 模块，Preview 不计入）\n\n");
            out.write("## 总览\n\n");
            out.write("| 指标 | 已使用 | 分母 | 覆盖率 |\n");
            out.write("|---|---:|---:|---:|\n");
            row(out, "类型", matchedTypes(), denominatorTypes());
            row(out, "包", matchedPackages(), denominatorPackages());
            row(out, "模块", matchedModules(), denominatorModules());
            out.write("\n扫描 class 文件 " + classCount() + " 个；Preview 违规 "
                    + (previewClasses.size() + previewViolations.size()) + " 项。\n\n");

            out.write("## 模块明细\n\n");
            out.write("| 模块 | 已使用 | 分母 | 覆盖率 |\n");
            out.write("|---|---:|---:|---:|\n");
            for (Map.Entry<String, Integer> e : moduleTotals.entrySet()) {
                int used = moduleUsed.getOrDefault(e.getKey(), 0);
                out.write("| " + e.getKey() + " | " + used + " | " + e.getValue()
                        + " | " + pct(used, e.getValue()) + " |\n");
            }

            out.write("\n## 预览违规\n\n");
            if (clean()) {
                out.write("无。核心模块未引用 Preview 类型，也未产出带预览标记的 class。\n");
            } else {
                for (String cls : previewClasses) {
                    out.write("- class 带预览标记：`" + cls + "`\n");
                }
                for (ApiType type : previewViolations) {
                    out.write("- 引用 Preview 类型：`" + type.binary() + "`（"
                            + type.module() + "）\n");
                }
            }

            out.write("\n## 未覆盖类型最多的包（前 20）\n\n");
            out.write("| 包 | 未使用 | 分母 |\n|---|---:|---:|\n");
            List<Map.Entry<String, Integer>> missing = new ArrayList<>();
            for (Map.Entry<String, Integer> e : packageTotals.entrySet()) {
                int used = packageUsed.getOrDefault(e.getKey(), 0);
                int gap = e.getValue() - used;
                if (gap > 0) {
                    missing.add(Map.entry(e.getKey(), gap));
                }
            }
            missing.sort(Map.Entry.<String, Integer>comparingByValue().reversed()
                    .thenComparing(Map.Entry.comparingByKey()));
            int shown = 0;
            for (Map.Entry<String, Integer> e : missing) {
                if (shown++ >= 20) {
                    break;
                }
                out.write("| " + e.getKey() + " | " + e.getValue() + " | "
                        + packageTotals.get(e.getKey()) + " |\n");
            }

            out.write("\n## 使用到的类型（按模块）\n\n");
            Map<String, List<String>> byModule = new TreeMap<>();
            for (String ref : usedByType.keySet()) {
                ApiType type = baselineByBinary.get(ref);
                if (type != null && type.inDenominator()) {
                    byModule.computeIfAbsent(type.module(), k -> new ArrayList<>()).add(ref);
                }
            }
            for (Map.Entry<String, List<String>> e : byModule.entrySet()) {
                List<String> types = e.getValue();
                types.sort(Comparator.naturalOrder());
                out.write("- **" + e.getKey() + "**（" + types.size() + "）："
                        + String.join("、", types) + "\n");
            }
            out.write("\n> 逐类型证据（引用计数与引用类名）见同目录 "
                    + "`api-usage.csv`。\n");
        }
    }

    public void writeCsv(Path file) throws IOException {
        createParent(file);
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("module,package,type_binary,coverage_scope,status,reference_count,"
                    + "referenced_by\n");
            List<ApiType> rows = new ArrayList<>(baseline.all());
            rows.sort(Comparator.comparing(ApiType::binary));
            for (ApiType type : rows) {
                TreeSet<String> refs = usedByType.get(type.binary());
                int count = refs == null ? 0 : refs.size();
                String status;
                if (!type.inDenominator()) {
                    status = "EXCLUDED";
                } else if (count > 0) {
                    status = "USED";
                } else {
                    status = "UNUSED";
                }
                String referencedBy = refs == null ? "" : String.join(";",
                        refs.stream().limit(16).toList());
                out.write(String.join(",",
                        Csv.escape(type.module()),
                        Csv.escape(type.packageName()),
                        Csv.escape(type.binary()),
                        Csv.escape(type.scope()),
                        status,
                        Integer.toString(count),
                        Csv.escape(referencedBy)));
                out.write('\n');
            }
        }
    }

    private static void row(BufferedWriter out, String label, int used, int total)
            throws IOException {
        out.write("| " + label + " | " + used + " | " + total + " | " + pct(used, total)
                + " |\n");
    }

    private static String pct(int used, int total) {
        return String.format(Locale.ROOT, "%.1f%%", percent(used, total));
    }

    private static double percent(int used, int total) {
        return total == 0 ? 0.0 : used * 100.0 / total;
    }

    private static void createParent(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
