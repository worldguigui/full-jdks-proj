package fc.audit;

import fc.testkit.FcTest;
import fc.testkit.TestRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** 审计模块测试：CSV 解析、classfile 扫描、预览标记与报告输出。 */
public final class AuditTest {

    private static final String HEADER = "module,package,type_binary,type_display,kind,"
            + "nesting,deprecated,for_removal,preview,preview_members,spec_status,"
            + "coverage_scope,extension_reason,frozen_status\n";

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(AuditTest.class))) {
            System.exit(1);
        }
    }

    private static Path tempDir() throws Exception {
        return Files.createTempDirectory("fc-audit-test-");
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static Path fixtureRoot() throws Exception {
        return Path.of(AuditTest.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).resolve("fc/audit/fixture");
    }

    private static Path tempBaseline(Path dir) throws Exception {
        String csv = HEADER
                + row("java.base", "java.util", "java.util.List", "interface")
                + row("java.base", "java.io", "java.io.File", "class")
                + row("java.base", "java.time", "java.time.LocalDate", "class")
                + row("java.base", "java.lang", "java.lang.String", "class")
                + row("java.base", "java.util.jar", "java.util.jar.JarFile", "class")
                + "java.base,java.lang,java.lang.StringTemplate,java.lang.StringTemplate,"
                + "interface,top,no,no,yes,no,SE,EXCLUDED_PREVIEW,,FROZEN\n";
        Path file = dir.resolve("baseline.csv");
        Files.writeString(file, csv, StandardCharsets.UTF_8);
        return file;
    }

    private static String row(String module, String pkg, String binary, String kind) {
        return module + "," + pkg + "," + binary + "," + binary + "," + kind
                + ",top,no,no,no,no,SE,IN_DENOMINATOR,,FROZEN\n";
    }

    @FcTest
    public void csvParserHandlesQuotesAndCommas() {
        List<String> fields = Csv.parseLine("a,\"b,c\",\"d\"\"e\",f");
        assert fields.equals(List.of("a", "b,c", "d\"e", "f")) : fields;
        assert Csv.escape("x,y").equals("\"x,y\"");
        assert Csv.escape("plain").equals("plain");
    }

    @FcTest
    public void baselineReadsDenominatorAndPreview() throws Exception {
        Path dir = tempDir();
        try {
            Baseline baseline = Baseline.read(tempBaseline(dir));
            assert baseline.all().size() == 6 : baseline.all().size();
            assert baseline.denominator().size() == 5 : baseline.denominator().size();
            assert baseline.packages().contains("java.util");
            assert baseline.modules().equals(java.util.Set.of("java.base"));
            assert baseline.byBinary().get("java.util.List").inDenominator();
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void scannerExtractsRealTypeReferences() throws Exception {
        Path classFile = fixtureRoot().resolve("UsesApis.class");
        assert Files.isRegularFile(classFile) : classFile;
        ClassScanner.Result result = ClassScanner.scan(classFile);
        assert result.className().equals("fc.audit.fixture.UsesApis") : result.className();
        assert result.references().contains("java.util.List") : result.references();
        assert result.references().contains("java.io.File");
        assert result.references().contains("java.time.LocalDate");
        assert !result.preview();
    }

    @FcTest
    public void reportCountsCoverageAndWritesEvidence() throws Exception {
        Path dir = tempDir();
        try {
            Baseline baseline = Baseline.read(tempBaseline(dir));
            List<ClassScanner.Result> classes = ClassScanner.scanTree(fixtureRoot());
            AuditReport report = new AuditReport(baseline, classes);
            assert report.classCount() >= 1;
            assert report.matchedTypes() >= 4 : report.summary();
            assert report.denominatorTypes() == 5;
            assert report.matchedPackages() >= 3 : report.summary();
            assert report.denominatorPackages() == 5;
            assert report.clean() : report.previewViolations();

            Path md = dir.resolve("out/report.md");
            Path csv = dir.resolve("out/usage.csv");
            report.writeMarkdown(md);
            report.writeCsv(csv);
            String markdown = Files.readString(md, StandardCharsets.UTF_8);
            assert markdown.contains("API 使用审计报告");
            assert markdown.contains("扫描 class 文件");
            String evidence = Files.readString(csv, StandardCharsets.UTF_8);
            assert evidence.contains("java.util.List") : evidence;
            assert evidence.contains(",USED,") : evidence;
            assert evidence.contains("java.util.jar.JarFile") && evidence.contains(",UNUSED,");
            assert evidence.contains("EXCLUDED");
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void previewClassFlagIsDetected() throws Exception {
        Path dir = tempDir();
        try {
            Path original = fixtureRoot().resolve("UsesApis.class");
            byte[] bytes = Files.readAllBytes(original);
            bytes[4] = (byte) 0xFF;
            bytes[5] = (byte) 0xFF;
            Path patched = dir.resolve("UsesApis.class");
            Files.write(patched, bytes);
            ClassScanner.Result result = ClassScanner.scan(patched);
            assert result.preview() : "minor_version=0xFFFF 应被判为预览 class";
        } finally {
            deleteTree(dir);
        }
    }
}
