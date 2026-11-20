import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Java SE 21 规范公开 API 基准盘点工具（阶段一审计工具，非游戏代码）。
 *
 * 方法：
 *  1. 模块集：以 JSR 396 定义为准 —— java.se 聚合模块的 requires transitive 闭包
 *     （不含聚合模块自身）。另外单独纳入 java.smartcardio 并标记 spec_status=
 *     JAVA_PREFIX_ONLY（官方 API 文档按 java.* 前缀将其列入 "Java SE" 分组，但
 *     JSR 396 的 java.se 聚合图不包含它）。
 *  2. 包集：仅取模块描述符中"非限定导出"（unqualified exports）的包。
 *  3. 类型集：遍历 jrt:/ 运行时镜像中该模块下的 .class 文件，对导出包中的类
 *     执行反射加载（不初始化），仅保留 public 修饰的规范公开类型；嵌套公开类型
 *     独立计数。module-info / package-info / 匿名与局部类（名称含 $ 数字）排除。
 *  4. 标记：@Deprecated、@Deprecated(forRemoval=true)、@PreviewFeature（预览）。
 *
 * 输出（写入 --out 目录）：
 *  - api-baseline.csv   全部纳入统计的公开类型（后续覆盖审计的分母数据源）
 *  - packages.csv       包级统计
 *  - load-failures.csv  反射加载失败的类型（应为空，非空则需人工核验）
 *  - stdout 打印汇总统计
 *
 * 运行（JDK 21）：
 *   D:/Java/ms-21.0.13.0.2/bin/java tools/ApiInventoryScanner.java --out docs/phase1/data
 */
public final class ApiInventoryScanner {

    /** java.* 前缀但不在 java.se 聚合图中的官方文档 "Java SE" 分组模块。 */
    private static final Set<String> JAVA_PREFIX_ONLY_MODULES = Set.of("java.smartcardio");

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of("docs/phase1/data");
        for (int i = 0; i < args.length - 1; i++) {
            if ("--out".equals(args[i])) outDir = Path.of(args[i + 1]);
        }
        Files.createDirectories(outDir);

        ModuleFinder system = ModuleFinder.ofSystem();

        // ---- 1. Java SE 模块集：java.se 的 requires transitive 闭包 ----
        //（java.se 聚合模块自身不导出包，不计入；classpath 模式下引导层不含
        //  java.se，故基于 ModuleFinder 描述符解析，而非运行时层。）
        LinkedHashSet<String> seModules = new LinkedHashSet<>();
        ArrayDeque<String> todo = new ArrayDeque<>();
        todo.add("java.se");
        while (!todo.isEmpty()) {
            String name = todo.poll();
            if (!seModules.add(name)) continue;
            ModuleDescriptor desc = system.find(name)
                    .orElseThrow(() -> new IllegalStateException("missing module " + name)).descriptor();
            for (ModuleDescriptor.Requires r : desc.requires()) {
                boolean transitive = r.modifiers()
                        .contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE);
                if (transitive || "java.base".equals(r.name())) todo.add(r.name());
            }
        }
        seModules.remove("java.se"); // 聚合模块自身，不导出任何包

        LinkedHashSet<String> scanModules = new LinkedHashSet<>(seModules);
        scanModules.addAll(JAVA_PREFIX_ONLY_MODULES);
        for (String n : scanModules) {
            if (system.find(n).isEmpty()) throw new IllegalStateException("missing module " + n);
        }

        // ---- 2/3/4. 遍历 jrt 镜像 ----
        // 类加载使用 platform 类加载器（父委托覆盖引导加载器）：java.compiler、
        // java.sql 等模块的类定义在 platform 加载器，Class.forName(…, null) 找不到。
        ClassLoader loader = ClassLoader.getPlatformClassLoader();
        FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        List<String[]> rows = new ArrayList<>(); // module, pkg, binary, display, kind, nested, dep, rem, prev, status
        List<String> loadFailures = new ArrayList<>();
        final String PREVIEW_MARKER = "jdk/internal/javac/PreviewFeature";

        for (String modName : scanModules.stream().sorted().collect(Collectors.toList())) {
            ModuleDescriptor desc = system.find(modName).orElseThrow().descriptor();
            Set<String> exported = desc.exports().stream()
                    .filter(e -> !e.isQualified())
                    .map(ModuleDescriptor.Exports::source)
                    .collect(Collectors.toSet());
            String specStatus = seModules.contains(modName) ? "SE" : "JAVA_PREFIX_ONLY";
            Path root = jrt.getPath("/modules/" + modName);
            if (!Files.isDirectory(root)) throw new IllegalStateException("no jrt root for " + modName);
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .sorted()
                        .forEach(p -> {
                            String rel = root.relativize(p).toString().replace('\\', '/');
                            if (!rel.endsWith(".class")) return;
                            String binary = rel.substring(0, rel.length() - ".class".length());
                            String pkg = binary.contains("/")
                                    ? binary.substring(0, binary.lastIndexOf('/')).replace('/', '.')
                                    : "";
                            String cls = binary.substring(binary.lastIndexOf('/') + 1);
                            if (cls.equals("module-info") || cls.equals("package-info")) return;
                            if (cls.matches(".*\\$\\d+.*")) return; // 匿名/局部/编号合成类
                            if (!exported.contains(pkg)) return;
                            String fqBinary = binary.replace('/', '.');
                            Class<?> c;
                            try {
                                c = Class.forName(fqBinary, false, loader);
                            } catch (Throwable t) {
                                loadFailures.add(modName + "," + fqBinary + "," + t.getClass().getName());
                                return;
                            }
                            if (!Modifier.isPublic(c.getModifiers())) return;
                            if (c.isHidden() || c.isSynthetic()) return;
                            // 嵌套类型仅在外围类链全部 public 时才可达（否则 javadoc
                            // 不生成文档，如 java.lang.WeakPairMap.Pair 系列）。
                            for (Class<?> e = c.getEnclosingClass(); e != null; e = e.getEnclosingClass()) {
                                if (!Modifier.isPublic(e.getModifiers())) return;
                            }

                            String kind;
                            if (c.isAnnotation()) kind = "annotation";
                            else if (c.isEnum()) kind = "enum";
                            else if (c.isRecord()) kind = "record";
                            else if (c.isInterface()) kind = c.isSealed() ? "sealed-interface" : "interface";
                            else kind = c.isSealed() ? "sealed-class"
                                    : Modifier.isAbstract(c.getModifiers()) ? "abstract-class" : "class";

                            // 类型级预览：精确解析 class 文件类级 RuntimeInvisible/VisibleAnnotations。
                            // @PreviewFeature 为 CLASS 保留，运行时反射不可见。
                            // 成员级预览（方法被标注而类型本身不是预览 API）另行记录。
                            boolean previewClass, previewMembers;
                            try {
                                byte[] bytes = Files.readAllBytes(p);
                                previewClass = classLevelPreview(bytes);
                                previewMembers = previewClass
                                        || new String(bytes, StandardCharsets.ISO_8859_1)
                                                .contains("jdk/internal/javac/PreviewFeature");
                            } catch (Exception e) {
                                throw new IllegalStateException("read failed: " + p, e);
                            }
                            Deprecated dep = c.getAnnotation(Deprecated.class);
                            String display = c.getCanonicalName() != null ? c.getCanonicalName() : fqBinary;
                            rows.add(new String[] {
                                    modName, pkg, fqBinary, display, kind,
                                    cls.contains("$") ? "nested" : "top",
                                    dep != null ? "yes" : "no",
                                    dep != null && dep.forRemoval() ? "yes" : "no",
                                    previewClass ? "yes" : "no",
                                    previewMembers ? "yes" : "no",
                                    specStatus });
                        });
            }
        }

        // ---- 统计与输出 ----
        rows.sort(Comparator.comparing((String[] r) -> r[0]).thenComparing(r -> r[1]).thenComparing(r -> r[2]));

        StringBuilder csv = new StringBuilder(
                "module,package,type_binary,type_display,kind,nesting,deprecated,for_removal,preview,preview_members,spec_status\n");
        for (String[] r : rows) {
            csv.append(String.join(",", r[0], r[1], q(r[2]), q(r[3]), r[4], r[5],
                    r[6], r[7], r[8], r[9], r[10])).append('\n');
        }
        Files.writeString(outDir.resolve("api-baseline.csv"), csv.toString(), StandardCharsets.UTF_8);

        // 包级统计
        Map<String, int[]> byPkg = new LinkedHashMap<>(); // types, dep, rem, prev
        for (String[] r : rows) {
            int[] a = byPkg.computeIfAbsent(r[0] + "|" + r[1], k -> new int[4]);
            a[0]++;
            if ("yes".equals(r[6])) a[1]++;
            if ("yes".equals(r[7])) a[2]++;
            if ("yes".equals(r[8])) a[3]++;
        }
        StringBuilder pkgCsv = new StringBuilder("module,package,types,deprecated,for_removal,preview,spec_status\n");
        byPkg.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            int[] a = e.getValue();
            String[] parts = e.getKey().split("\\|", 2);
            String status = seModules.contains(parts[0]) ? "SE" : "JAVA_PREFIX_ONLY";
            pkgCsv.append(String.join(",", parts[0], parts[1],
                    String.valueOf(a[0]), String.valueOf(a[1]), String.valueOf(a[2]),
                    String.valueOf(a[3]), status)).append('\n');
        });
        Files.writeString(outDir.resolve("packages.csv"), pkgCsv.toString(), StandardCharsets.UTF_8);

        Files.writeString(outDir.resolve("load-failures.csv"),
                loadFailures.isEmpty() ? "" : String.join("\n", loadFailures) + "\n", StandardCharsets.UTF_8);

        // stdout 汇总
        long total = rows.size();
        long top = rows.stream().filter(r -> "top".equals(r[5])).count();
        long nested = total - top;
        long dep = rows.stream().filter(r -> "yes".equals(r[6])).count();
        long rem = rows.stream().filter(r -> "yes".equals(r[7])).count();
        long prev = rows.stream().filter(r -> "yes".equals(r[8])).count();
        long prevMembers = rows.stream().filter(r -> "yes".equals(r[9])).count();
        Map<String, Long> byKind = rows.stream().collect(Collectors.groupingBy(r -> r[4], TreeMap::new, Collectors.counting()));

        System.out.println("== Java SE 21 API baseline ==");
        System.out.println("JDK: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        System.out.println("SE modules (java.se closure): " + seModules.size() + " -> " + seModules.stream().sorted().collect(Collectors.toList()));
        System.out.println("extra java.*-prefix modules:  " + JAVA_PREFIX_ONLY_MODULES);
        System.out.println("packages: " + byPkg.size());
        System.out.println("public types total: " + total + " (top-level " + top + ", nested " + nested + ")");
        System.out.println("by kind: " + byKind);
        System.out.println("deprecated: " + dep + " (forRemoval " + rem + ")");
        System.out.println("preview (class-level @PreviewFeature): " + prev
                + " (+ member-level-only references: " + (prevMembers - prev) + ")");
        System.out.println("load failures: " + loadFailures.size());
        Map<String, Long> modTypes = rows.stream().collect(Collectors.groupingBy(r -> r[0], TreeMap::new, Collectors.counting()));
        System.out.println("-- per module (packages/types) --");
        byPkg.keySet().stream().map(k -> k.split("\\|", 2)[0]).distinct().sorted()
                .forEach(m -> System.out.printf("  %-22s %4d pkgs %5d types%n", m,
                        byPkg.keySet().stream().filter(k -> k.startsWith(m + "|")).count(), modTypes.get(m)));
        System.out.println("outputs -> " + outDir.toAbsolutePath());
    }

    private static String q(String s) {
        if (s.contains(",") || s.contains("\"")) return '"' + s.replace("\"", "\"\"") + '"';
        return s;
    }

    // ---- 最小 class 文件解析（仅读取类级注解，用于 @PreviewFeature 精确检测） ----

    private static int u2(byte[] b, int p) { return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF); }

    private static int u4(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    private static boolean classLevelPreview(byte[] b) {
        try {
            int p = 8; // magic(4) + minor(2) + major(2)
            int cpCount = u2(b, p); p += 2;
            String[] cp = new String[cpCount];
            for (int i = 1; i < cpCount; i++) {
                int tag = b[p++] & 0xFF;
                switch (tag) {
                    case 1: { // Utf8
                        int len = u2(b, p); p += 2;
                        cp[i] = new String(b, p, len, StandardCharsets.ISO_8859_1);
                        p += len;
                        break;
                    }
                    case 3: case 4: p += 4; break;          // Integer, Float
                    case 5: case 6: p += 8; i++; break;     // Long, Double（双槽）
                    case 7: case 8: case 16: case 19: case 20: p += 2; break;
                    case 9: case 10: case 11: case 12: case 17: case 18: p += 4; break;
                    case 15: p += 3; break;                 // MethodHandle
                    default: return false;                  // 未知 tag，放弃解析
                }
            }
            p += 6;                    // access_flags, this_class, super_class
            int ifc = u2(b, p); p += 2 + 2 * ifc;   // interfaces
            p = skipMembers(b, p);     // fields
            p = skipMembers(b, p);     // methods
            int nAttrs = u2(b, p); p += 2;          // 类级属性
            for (int a = 0; a < nAttrs; a++) {
                int nameIdx = u2(b, p); p += 2;
                int len = u4(b, p); p += 4;
                String name = cp[nameIdx];
                if ("RuntimeInvisibleAnnotations".equals(name) || "RuntimeVisibleAnnotations".equals(name)) {
                    int end = p + len;
                    int nAnn = u2(b, p); p += 2;
                    for (int an = 0; an < nAnn && p < end; an++) {
                        int typeIdx = u2(b, p); p += 2;
                        if ("Ljdk/internal/javac/PreviewFeature;".equals(cp[typeIdx])) return true;
                        p = skipAnnotationBody(b, p);
                    }
                } else {
                    p += len;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int skipMembers(byte[] b, int p) {
        int count = u2(b, p); p += 2;
        for (int m = 0; m < count; m++) {
            p += 6; // access_flags, name_index, descriptor_index
            p = skipAttributes(b, p);
        }
        return p;
    }

    private static int skipAttributes(byte[] b, int p) {
        int count = u2(b, p); p += 2;
        for (int a = 0; a < count; a++) {
            p += 2; // attribute_name_index
            int len = u4(b, p); p += 4;
            p += len; // info[length]
        }
        return p;
    }

    private static int skipAnnotationBody(byte[] b, int p) {
        int pairs = u2(b, p); p += 2;
        for (int i = 0; i < pairs; i++) {
            p += 2; // element_name_index
            p = skipElementValue(b, p);
        }
        return p;
    }

    private static int skipElementValue(byte[] b, int p) {
        char tag = (char) b[p++];
        switch (tag) {
            case 'B': case 'C': case 'D': case 'F': case 'I': case 'J':
            case 'S': case 'Z': case 's': case 'c':
                p += 2; break;           // 常量池索引
            case 'e': p += 4; break;     // enum: type_class_index + const_name_index
            case '@': p += 2; p = skipAnnotationBody(b, p); break;
            case '[': {
                int n = u2(b, p); p += 2;
                for (int i = 0; i < n; i++) p = skipElementValue(b, p);
                break;
            }
            default: break;
        }
        return p;
    }
}
