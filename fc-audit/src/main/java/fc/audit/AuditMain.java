package fc.audit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 审计入口：扫描各模块 class 产物，对照阶段一冻结基准生成覆盖率证据。
 *
 * <p>用法：</p>
 * <pre>
 * java -cp ... fc.audit.AuditMain
 *      [--baseline docs/phase1/data/coverage-baseline.csv]
 *      [--classes 目录]...        # 可重复；缺省扫描 ./fc-* /target/classes
 *      [--out 目录]               # 缺省 target/api-usage
 * </pre>
 *
 * <p>退出码：0 = 报告生成且无 Preview 违规；1 = 存在 Preview 违规或输入错误。
 * 覆盖率本身不设硬失败阈值——目标是诚实审计，而不是为数字塞无意义调用。</p>
 */
public final class AuditMain {

    private AuditMain() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (IOException e) {
            System.err.println("审计失败：" + e.getMessage());
            System.exit(1);
        }
    }

    /** 可测试的入口：返回退出码。 */
    public static int run(String[] args) throws IOException {
        Path baselinePath = Path.of("docs/phase1/data/coverage-baseline.csv");
        Path outDir = Path.of("target/api-usage");
        List<Path> classDirs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--baseline" -> {
                    baselinePath = Path.of(require(args, ++i, "--baseline"));
                }
                case "--classes" -> classDirs.add(Path.of(require(args, ++i, "--classes")));
                case "--out" -> outDir = Path.of(require(args, ++i, "--out"));
                case "--help", "-h" -> {
                    System.out.println("用法: AuditMain [--baseline CSV] "
                            + "[--classes DIR]... [--out DIR]");
                    return 0;
                }
                default -> throw new IllegalArgumentException("未知参数: " + args[i]);
            }
        }
        if (classDirs.isEmpty()) {
            classDirs = discoverClassDirs();
        }
        if (classDirs.isEmpty()) {
            throw new IOException("找不到任何 class 目录；先执行 mvn compile");
        }

        Baseline baseline = Baseline.read(baselinePath);
        List<ClassScanner.Result> results = new ArrayList<>();
        for (Path dir : classDirs) {
            results.addAll(ClassScanner.scanTree(dir));
        }
        AuditReport report = new AuditReport(baseline, results);
        Files.createDirectories(outDir);
        Path markdown = outDir.resolve("api-usage-report.md");
        Path csv = outDir.resolve("api-usage.csv");
        report.writeMarkdown(markdown);
        report.writeCsv(csv);

        System.out.println("基准：" + baselinePath + "（分母 " + report.denominatorTypes()
                + " 类型 / " + report.denominatorPackages() + " 包）");
        System.out.println("扫描目录：" + classDirs.size() + " 个，class "
                + report.classCount() + " 个");
        System.out.println(report.summary());
        System.out.println("报告：" + markdown);
        System.out.println("证据：" + csv);
        if (!report.clean()) {
            System.err.println("Preview 违规：");
            report.previewClasses().forEach(c -> System.err.println("  class " + c));
            report.previewViolations().forEach(t -> System.err.println("  引用 " + t.binary()));
            return 1;
        }
        return 0;
    }

    private static String require(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " 缺少参数");
        }
        return args[index];
    }

    /** 扫描当前目录下 {@code fc-*}{@code /target/classes}。 */
    private static List<Path> discoverClassDirs() throws IOException {
        List<Path> dirs = new ArrayList<>();
        try (var stream = Files.list(Path.of("."))) {
            for (Path dir : stream.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("fc-"))
                    .sorted()
                    .toList()) {
                Path classes = dir.resolve("target/classes");
                if (Files.isDirectory(classes)) {
                    dirs.add(classes);
                }
            }
        }
        dirs.sort(java.util.Comparator.comparing(Path::toString));
        return dirs;
    }
}
