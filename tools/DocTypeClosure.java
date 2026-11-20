import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * javadoc 文档化类型闭包枚举工具（阶段一差异审计的推导侧）。
 *
 * javadoc 文档化规则（默认 -protected 模式）：
 *   - 顶层类型：public 才文档化；
 *   - 嵌套类型：其直接外围类型已文档化，且自身 access 为 public 或 protected。
 * 该规则与实证一致：官方全类索引含静态嵌套（含 9 个 protected），非静态内部类
 * 有文档页但不出现在索引中（已用 AbstractQueuedSynchronizer.ConditionObject 验证）。
 *
 * 本工具从运行时 jrt 镜像按此规则计算"文档化类型闭包"，并与 api-baseline.csv
 * （public 口径扫描结果）比对，输出：
 *   doc-closure.csv       全部文档化类型（含 protected 嵌套）及是否在基准中
 *   closure-missing.csv   文档化但不在 public 基准中的类型（缺失候选）
 *   closure-extra.csv     基准中但不在文档化闭包中的类型（应为空）
 *
 * 注意：闭包是"推导 + 规则"产物，逐类型证据须由文档页抓取（见 verify 脚本）。
 * 运行：D:/Java/ms-21.0.13.0.2/bin/java tools/DocTypeClosure.java
 */
public final class DocTypeClosure {

    private static final Set<String> SCOPE_MODULES = Set.of(
            "java.base", "java.compiler", "java.datatransfer", "java.desktop",
            "java.instrument", "java.logging", "java.management", "java.management.rmi",
            "java.naming", "java.net.http", "java.prefs", "java.rmi", "java.scripting",
            "java.security.jgss", "java.security.sasl", "java.smartcardio", "java.sql",
            "java.sql.rowset", "java.transaction.xa", "java.xml", "java.xml.crypto");

    record Info(String binary, String display, String kind, String access, String nesting, String module, String pkg) {}

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of("docs/phase1/data/verification");
        ModuleFinder system = ModuleFinder.ofSystem();
        FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        ClassLoader loader = ClassLoader.getPlatformClassLoader();

        Map<String, Info> all = new TreeMap<>();      // binary -> info（导出包内全部可链接类型）
        for (String modName : SCOPE_MODULES.stream().sorted().collect(Collectors.toList())) {
            ModuleDescriptor desc = system.find(modName).orElseThrow().descriptor();
            Set<String> exported = desc.exports().stream()
                    .filter(e -> !e.isQualified())
                    .map(ModuleDescriptor.Exports::source)
                    .collect(Collectors.toSet());
            Path root = jrt.getPath("/modules/" + modName);
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .map(p -> root.relativize(p).toString().replace('\\', '/'))
                        .filter(s -> s.endsWith(".class"))
                        .forEach(rel -> {
                            String binary = rel.substring(0, rel.length() - 6);
                            String pkg = binary.contains("/")
                                    ? binary.substring(0, binary.lastIndexOf('/')).replace('/', '.') : "";
                            String cls = binary.substring(binary.lastIndexOf('/') + 1);
                            if (cls.equals("module-info") || cls.equals("package-info")) return;
                            if (cls.matches(".*\\$\\d+.*")) return;
                            if (!exported.contains(pkg)) return;
                            String fq = binary.replace('/', '.');
                            Class<?> c;
                            try { c = Class.forName(fq, false, loader); }
                            catch (Throwable t) { return; }
                            if (c.isHidden() || c.isSynthetic()) return;
                            int mods = c.getModifiers();
                            String access = Modifier.isPublic(mods) ? "public"
                                    : Modifier.isProtected(mods) ? "protected" : "other";
                            String kind = c.isAnnotation() ? "annotation" : c.isEnum() ? "enum"
                                    : c.isRecord() ? "record" : c.isInterface()
                                            ? (c.isSealed() ? "sealed-interface" : "interface")
                                            : c.isSealed() ? "sealed-class"
                                                    : Modifier.isAbstract(mods) ? "abstract-class" : "class";
                            all.put(fq, new Info(fq,
                                    c.getCanonicalName() != null ? c.getCanonicalName() : fq,
                                    kind, access, cls.contains("$") ? "nested" : "top", modName, pkg));
                        });
            }
        }

        // ---- javadoc 文档化闭包：顶层 public 起，嵌套 public|protected 逐步纳入 ----
        Set<String> doc = new HashSet<>();
        for (Info i : all.values()) {
            Class<?> c = Class.forName(i.binary(), false, loader);
            if (c.getEnclosingClass() == null && "public".equals(i.access())) doc.add(i.binary());
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Info i : all.values()) {
                if (doc.contains(i.binary())) continue;
                if (!"public".equals(i.access()) && !"protected".equals(i.access())) continue;
                Class<?> c = Class.forName(i.binary(), false, loader);
                Class<?> enc = c.getEnclosingClass();
                if (enc != null && doc.contains(enc.getName())) {
                    doc.add(i.binary());
                    grew = true;
                }
            }
        }

        // ---- 与 public 基准比对 ----
        Set<String> baseline = Files.readAllLines(Path.of("docs/phase1/data/api-baseline.csv"),
                StandardCharsets.UTF_8).stream()
                .filter(l -> !l.startsWith("module,"))
                .map(l -> l.split(",", -1)[2]) // type_binary
                .collect(Collectors.toCollection(TreeSet::new));

        List<String> closureRows = new ArrayList<>();
        List<String> missing = new ArrayList<>(), extra = new ArrayList<>();
        List<String> fetchMissing = new ArrayList<>();
        String base = "https://docs.oracle.com/en/java/javase/21/docs/api/";
        for (String b : new TreeSet<>(doc)) {
            Info i = all.get(b);
            boolean inBase = baseline.contains(b);
            closureRows.add(b + "," + q(i.display()) + "," + i.kind() + "," + i.access() + "," + i.nesting()
                    + "," + (inBase ? "yes" : "no"));
            if (!inBase) {
                missing.add(b);
                String disp = i.display();
                String classPart = disp.startsWith(i.pkg() + ".")
                        ? disp.substring(i.pkg().length() + 1) : disp;
                String url = i.module() + "/" + i.pkg().replace('.', '/') + "/" + classPart + ".html";
                fetchMissing.add(b + "|" + base + url);
            }
        }
        for (String b : baseline) if (!doc.contains(b)) extra.add(b);

        Files.write(outDir.resolve("doc-closure.csv"),
                (String.join(",", "type_binary", "type_display", "kind", "access", "nesting", "in_public_baseline")
                        + "\n" + String.join("\n", closureRows) + "\n").getBytes(StandardCharsets.UTF_8));
        Files.write(outDir.resolve("closure-missing.csv"),
                ("type_binary\n" + (missing.isEmpty() ? "" : String.join("\n", missing) + "\n"))
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(outDir.resolve("closure-extra.csv"),
                ("type_binary\n" + (extra.isEmpty() ? "" : String.join("\n", extra) + "\n"))
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(outDir.resolve("fetch-missing.txt"),
                (String.join("\n", fetchMissing) + "\n").getBytes(StandardCharsets.UTF_8));

        System.out.println("导出包内可链接类型总数: " + all.size());
        System.out.println("javadoc 文档化闭包:     " + doc.size());
        System.out.println("  其中 public:          " + doc.stream().filter(b -> "public".equals(all.get(b).access())).count());
        System.out.println("  其中 protected:       " + doc.stream().filter(b -> "protected".equals(all.get(b).access())).count());
        System.out.println("public 基准(扫描器):    " + baseline.size());
        System.out.println("文档化但不在基准(缺失): " + missing.size() + " -> " + outDir.resolve("closure-missing.csv"));
        System.out.println("基准但不在文档化(额外): " + extra.size());
        Map<String, Long> missingByModule = missing.stream()
                .collect(Collectors.groupingBy(b -> moduleOf(b), TreeMap::new, Collectors.counting()));
        System.out.println("缺失按模块: " + missingByModule);
    }

    private static String moduleOf(String binary) {
        String pkg = binary.contains(".") ? binary.substring(0, binary.lastIndexOf('.')) : "";
        if (pkg.startsWith("java.applet") || pkg.startsWith("java.awt") && !pkg.contains("geom"))
            return "java.desktop";
        // 简化：由闭包文件无法直接得到模块，这里仅展示用
        return "mixed";
    }

    private static String q(String s) {
        return s.contains(",") ? '"' + s + '"' : s;
    }
}
