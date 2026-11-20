import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 阶段一全量差异审计工具：官方 Java SE 21 API 文档索引 vs 本地扫描器基准。
 *
 * 规范侧证据（人工下载，存于 docs/phase1/data/verification/）：
 *   allclasses-index.html   官方全类型索引（javadoc 生成，href 含模块名）
 *   allpackages-index.html  官方全包索引
 *
 * 比对范围：21 个模块（20 个 JSR 396 §7 模块 + java.smartcardio）。
 * 输出（同一目录）：
 *   diff-missing.csv        文档有、扫描器无（缺失类型）
 *   diff-extra.csv          扫描器有、文档无（额外类型）
 *   diff-kind-mismatch.csv  同名类型但种类族不符
 *   diff-indeterminate.csv  无法判断的条目（解析失败/范围不明）
 *   diff-packages.csv       包集差异
 *
 * 原则：本工具只报告差异，不修改任何统计结果；差异解释由人工完成并写入报告。
 *
 * 运行：D:/Java/ms-21.0.13.0.2/bin/java tools/ApiSpecDiff.java
 */
public final class ApiSpecDiff {

    private static final Set<String> SCOPE_MODULES = Set.of(
            "java.base", "java.compiler", "java.datatransfer", "java.desktop",
            "java.instrument", "java.logging", "java.management", "java.management.rmi",
            "java.naming", "java.net.http", "java.prefs", "java.rmi", "java.scripting",
            "java.security.jgss", "java.security.sasl", "java.smartcardio", "java.sql",
            "java.sql.rowset", "java.transaction.xa", "java.xml", "java.xml.crypto");

    // 文档种类词 → 基准 kind 前缀族
    private static final Map<String, Set<String>> KIND_FAMILY = Map.of(
            "class", Set.of("class", "abstract-class", "sealed-class"),
            "interface", Set.of("interface", "sealed-interface"),
            "enum class", Set.of("enum"),
            "record class", Set.of("record"),
            "annotation interface", Set.of("annotation"),
            "annotation type", Set.of("annotation"));

    public record DocEntry(String module, String pkg, String name, String kindWord, String href) {}

    public static void main(String[] args) throws Exception {
        Path verDir = Path.of("docs/phase1/data/verification");
        Path baseline = Path.of("docs/phase1/data/api-baseline.csv");
        Path packages = Path.of("docs/phase1/data/packages.csv");

        // ---- 1. 载入扫描器基准 ----
        Map<String, String[]> scan = new TreeMap<>();   // key(pkg.display) -> row
        Map<String, Integer> scanModuleTypes = new TreeMap<>();
        for (String line : Files.readAllLines(baseline, StandardCharsets.UTF_8)) {
            if (line.startsWith("module,")) continue;
            String[] c = splitCsv(line);
            String key = c[3]; // type_display 已是全限定规范名
            scan.put(key, c);
            scanModuleTypes.merge(c[0], 1, Integer::sum);
        }
        Set<String> scanPkgs = Files.readAllLines(packages, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.startsWith("module,"))
                .map(l -> l.split(",", -1))
                .filter(a -> a.length >= 2)
                .map(a -> a[0] + "|" + a[1])
                .collect(Collectors.toCollection(TreeSet::new));

        // ---- 2. 解析官方全类型索引 ----
        // 只取 col-first（类型名列）的锚点：描述列（col-last）中也有 {@link} 锚点，
        // 会产生假条目；类型名文本可能含 <wbr>（泛型逗号后换行标记），须剥离。
        String html = Files.readString(verDir.resolve("allclasses-index.html"), StandardCharsets.UTF_8);
        Pattern link = Pattern.compile(
                "<div class=\"col-first[^\"]*\">\\s*<a href=\"([^\"]+\\.html)\" title=\"([^\"]+)\">(.*?)</a>",
                Pattern.DOTALL);
        Pattern titleKind = Pattern.compile("^(class|interface|enum class|record class|annotation interface|annotation type) in (.+)$");
        Map<String, DocEntry> docs = new TreeMap<>();   // key -> entry
        List<String> indeterminate = new ArrayList<>();
        int outOfScope = 0, docJavaEntries = 0;
        Matcher m = link.matcher(html);
        while (m.find()) {
            String href = m.group(1);
            String title = m.group(2).trim();
            String text = m.group(3).replace("<wbr>", "").trim();
            Matcher tk = titleKind.matcher(title);
            if (!tk.matches()) {
                // href 是类型页但 title 不含种类词 —— 记为无法判断
                if (href.indexOf('/') > 0 && !href.endsWith("/package-summary.html")) {
                    String mod = href.substring(0, href.indexOf('/'));
                    if (SCOPE_MODULES.contains(mod)) {
                        indeterminate.add("TITLE-PARSE," + mod + "," + href + "," + title + "," + text);
                    }
                }
                continue;
            }
            String kindWord = tk.group(1);
            String pkg = tk.group(2).trim();
            String mod = href.substring(0, href.indexOf('/'));
            if (!SCOPE_MODULES.contains(mod)) { outOfScope++; continue; }
            docJavaEntries++;
            String name = text;
            int lt = name.indexOf("&lt;");
            if (lt >= 0) name = name.substring(0, lt).trim(); // 剥离泛型 &lt;T&gt;
            name = name.replace("&amp;", "&");
            if (name.isEmpty() || name.contains(" ") || name.contains("$")) {
                indeterminate.add("NAME-ODD," + mod + "," + href + "," + title + "," + text);
                continue;
            }
            String key = pkg + "." + name;
            DocEntry prev = docs.put(key, new DocEntry(mod, pkg, name, kindWord, href));
            if (prev != null) {
                indeterminate.add("DUP-DOC-ENTRY," + mod + "," + href + "," + prev.href + "," + key);
            }
        }

        // ---- 3. 解析官方全包索引 ----
        // 同样只取 col-first 列（包名列），排除描述列 {@link}。
        String pkgHtml = Files.readString(verDir.resolve("allpackages-index.html"), StandardCharsets.UTF_8);
        Pattern pkgLink = Pattern.compile(
                "<div class=\"col-first[^\"]*\">\\s*<a href=\"([^\"]+)/package-summary\\.html\">([^<]+)</a>");
        Set<String> docPkgs = new TreeSet<>();
        List<String> pkgIndeterminate = new ArrayList<>();
        int pkgOutOfScope = 0;
        Matcher pm = pkgLink.matcher(pkgHtml);
        while (pm.find()) {
            String mod = pm.group(1).substring(0, pm.group(1).indexOf('/'));
            String pkgName = pm.group(2).trim();
            if (!SCOPE_MODULES.contains(mod)) { pkgOutOfScope++; continue; }
            if (!docPkgs.add(mod + "|" + pkgName)) {
                pkgIndeterminate.add("DUP-PKG," + mod + "," + pkgName);
            }
        }

        // ---- 4. 差异计算 ----
        Set<String> docKeys = docs.keySet();
        Set<String> missing = new TreeSet<>(docKeys);
        missing.removeAll(scan.keySet());                       // 文档有、扫描无
        Set<String> extra = new TreeSet<>(scan.keySet());
        extra.removeAll(docKeys);                               // 扫描有、文档无
        List<String> kindMismatch = new ArrayList<>();
        for (Map.Entry<String, DocEntry> e : docs.entrySet()) {
            String[] row = scan.get(e.getKey());
            if (row == null) continue;
            Set<String> family = KIND_FAMILY.get(e.getValue().kindWord());
            if (family == null) { indeterminate.add("UNKNOWN-KIND-WORD," + e.getValue().kindWord() + "," + e.getKey()); continue; }
            if (!family.contains(row[4])) {
                kindMismatch.add(e.getKey() + ",doc=" + e.getValue().kindWord() + ",scan=" + row[4]);
            }
        }
        Set<String> pkgMissing = new TreeSet<>(docPkgs);
        pkgMissing.removeAll(scanPkgs);
        Set<String> pkgExtra = new TreeSet<>(scanPkgs);
        pkgExtra.removeAll(docPkgs);

        // ---- 5. 输出 ----
        write(verDir.resolve("diff-missing.csv"), "package.type,module,doc_kind,href", missing.stream()
                .map(k -> { DocEntry d = docs.get(k); return k + "," + d.module() + "," + d.kindWord() + "," + d.href(); })
                .collect(Collectors.toList()));
        write(verDir.resolve("diff-extra.csv"), "package.type,module,package,scan_kind,nesting,deprecated,preview,spec_status,url", extra.stream()
                .map(k -> { String[] r = scan.get(k);
                    String url = r[0] + "/" + r[1].replace('.', '/') + "/"
                            + k.substring(r[1].length() + 1) + ".html";
                    return k + "," + r[0] + "," + r[1] + "," + r[4] + "," + r[5] + "," + r[6] + "," + r[8] + "," + r[10] + "," + url; })
                .collect(Collectors.toList()));
        write(verDir.resolve("diff-kind-mismatch.csv"), "package.type,doc_kind,scan_kind", kindMismatch);
        write(verDir.resolve("diff-indeterminate.csv"), "reason,module_or_href,detail1,detail2",
                indeterminate.isEmpty() && pkgIndeterminate.isEmpty() ? List.of() :
                        concat(indeterminate, pkgIndeterminate));
        write(verDir.resolve("diff-packages.csv"), "direction,module,package",
                concat(toRows(pkgMissing, "docs-only"), toRows(pkgExtra, "scan-only")));

        System.out.println("== 全量差异审计（范围：21 模块）==");
        System.out.println("官方文档 java.* 类型条目: " + docJavaEntries + "（唯一 key " + docKeys.size() + "）");
        System.out.println("扫描器基准类型:          " + scan.size());
        System.out.println("缺失（文档有/扫描无）:   " + missing.size());
        System.out.println("额外（扫描有/文档无）:   " + extra.size());
        System.out.println("种类族不符:              " + kindMismatch.size());
        System.out.println("无法判断条目:            " + (indeterminate.size() + pkgIndeterminate.size()));
        System.out.println("文档范围外条目(jdk.*等): " + outOfScope + "；范围外包: " + pkgOutOfScope);
        System.out.println("包级差异: docs-only " + pkgMissing.size() + ", scan-only " + pkgExtra.size());
        if (!missing.isEmpty()) System.out.println("-- 缺失样例 --\n" + missing.stream().limit(30).collect(Collectors.joining("\n")));
        if (!extra.isEmpty()) System.out.println("-- 额外样例 --\n" + extra.stream().limit(30).collect(Collectors.joining("\n")));
        if (!kindMismatch.isEmpty()) System.out.println("-- 种类不符样例 --\n" + String.join("\n", kindMismatch.subList(0, Math.min(20, kindMismatch.size()))));
        if (!pkgMissing.isEmpty()) System.out.println("-- docs-only 包 --\n" + String.join("\n", pkgMissing));
        if (!pkgExtra.isEmpty()) System.out.println("-- scan-only 包 --\n" + String.join("\n", pkgExtra));
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> r = new ArrayList<>(a);
        r.addAll(b);
        return r;
    }

    private static List<String> toRows(Set<String> set, String direction) {
        return set.stream().map(s -> {
            String[] p = s.split("\\|", 2);
            return direction + "," + p[0] + "," + p[1];
        }).collect(Collectors.toList());
    }

    private static void write(Path p, String header, List<String> rows) throws Exception {
        StringBuilder sb = new StringBuilder(header).append('\n');
        rows.forEach(r -> sb.append(r).append('\n'));
        Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') { inQ = !inQ; continue; }
            if (ch == ',' && !inQ) { out.add(cur.toString()); cur.setLength(0); continue; }
            cur.append(ch);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
