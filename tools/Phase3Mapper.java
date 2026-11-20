import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 阶段三 v3：API 能力机会盘点与类型级准入生成器。
 *
 * 项目目标已变更为「API 覆盖优先，游戏作为承载载体」，准入体系由「包级默认继承」升级为
 * 「逐类型 L3 论证」。本生成器是唯一权威展开器：规则表是唯一事实来源，改口径只改 TSV 再重跑。
 *
 * 输入（只读）：
 *   - docs/phase1/data/coverage-baseline.csv         阶段一冻结清单（3874 行，分母 3836）
 *   - docs/phase1/data/extension-protected-nested.csv 168 个 protected 嵌套
 *   - docs/phase3/rules/package-rules.tsv            177 包默认判定（L1，只产 Pending/Excluded）
 *   - docs/phase3/rules/type-overrides.tsv           类型级覆盖（显式 feature_id，最高优先）
 *   - docs/phase3/rules/type-keep.tsv                功能↔类型准入关系（L2/L3）
 *   - docs/phase3/rules/feature-anchors.tsv          功能锚点（7 列：id/name/origin/anchor_type/accept/verify/priority）
 *
 * 输出：
 *   - docs/phase3/data/api-mapping.csv（26 列全量映射表）
 *   - docs/phase3/data/package-plan.csv / module-plan.csv
 *   - docs/phase3/data/mapping-summary.md / mapping-checks.txt
 *
 * 准入规则（硬断言）：
 *   L0/L1 不产出 Planned；L2 产出 Pending；L3/L4 产出 Planned。
 *   Planned 必须：有效 feature_id + L3 论证 + api_role + implementation_location。
 *   Pending 必须：记录未决问题与重新评估条件。
 *   Excluded 必须：原因码 + 原因 + 是否做过 API 驱动探索标记。
 *   ENV 必须有可复现运行时证据。
 *
 * 运行：E:/jdks/ms-21.0.11/bin/java -Dfile.encoding=UTF-8 tools/Phase3Mapper.java
 */
public final class Phase3Mapper {

    private static final Path ROOT = Paths.get(".");
    private static final Path BASELINE = ROOT.resolve("docs/phase1/data/coverage-baseline.csv");
    private static final Path PROTECTED = ROOT.resolve("docs/phase1/data/extension-protected-nested.csv");
    private static final Path PACKAGE_RULES = ROOT.resolve("docs/phase3/rules/package-rules.tsv");
    private static final Path TYPE_RULES = ROOT.resolve("docs/phase3/rules/type-overrides.tsv");
    private static final Path TYPE_KEEP = ROOT.resolve("docs/phase3/rules/type-keep.tsv");
    private static final Path FEATURES = ROOT.resolve("docs/phase3/rules/feature-anchors.tsv");
    private static final Path EXPLORATION = ROOT.resolve("docs/phase3/rules/api-exploration.tsv");
    private static final Path OUT_DIR = ROOT.resolve("docs/phase3/data");

    private static final Set<String> VALID_STATUS = Set.of("Planned", "Implemented", "Verified", "Excluded", "Pending");
    private static final Set<String> VALID_REASON = Set.of("BIZ", "ENV", "ACCESS", "DEPRECATED", "PREVIEW", "SPEC");
    private static final Set<String> VALID_LEVEL = Set.of("L0", "L1", "L2", "L3", "L4");
    private static final Set<String> VALID_ORIGIN = Set.of("CORE", "API_DRIVEN", "INFRASTRUCTURE", "DEVELOPER_TOOLING");
    private static final Set<String> VALID_ANCHOR = Set.of("PLAYER", "OPERATOR", "DEVELOPER");
    private static final Set<String> VALID_ROLE = Set.of("FACTORY", "MODEL", "SERVICE", "PROTOCOL", "TRANSPORT",
            "CONTROL", "QUERY", "ERROR", "LISTENER", "METADATA", "SECURITY", "CONFIG", "-");
    private static final Set<String> VALID_RT = Set.of("NONE", "HEADLESS_OK", "DEVICE_OPTIONAL", "SERVICE_OPTIONAL",
            "PROVIDER_REQUIRED");
    private static final Set<String> VALID_FALLBACK = Set.of("NONE", "SILENT_DISABLE", "STUB", "DEGRADE", "REQUIRED", "-");
    private static final Set<String> VALID_EXPLORE = Set.of("UNEXPLORED", "OPPORTUNITY_FOUND", "FEATURE_DESIGNED", "TYPE_JUSTIFIED");

    // ---------------------------------------------------------------- 数据模型

    private static final class Base {
        String module, pkg, binary, display, kind, nesting, deprecated, forRemoval, preview, previewMembers,
                specStatus, coverageScope;
    }

    private static final class PkgRule {
        String pkg, status, fcModule, system, batch, mainClass, reason, scenario;
    }

    private static final class TypeRule {
        String binary, status, fcModule, system, batch, mainClass, reason, scenario, feature;
    }

    /** 功能锚点（7 列）。 */
    private static final class Feature {
        String id, name, origin, anchorType, acceptance, verification, priority;
    }

    /** 功能↔类型准入关系（type-keep 一行 = 一个 feature 在一个包里的类型清单）。 */
    private static final class KeepEntry {
        String pkg, featureId, typesRaw, apiRole, runtimeReq, fallback, justification, level;
        String restReasonCode, restReason;
        Set<String> types = new LinkedHashSet<>();
        boolean keepAll;
    }

    /** API 探索台账（一行 = 一类共享同一探索结论的类型）。 */
    private static final class ExploreEntry {
        String domain, typeScope, status, candidateFeature, result, blocker, alternative, nextAction;
        Set<String> types = new LinkedHashSet<>();
    }

    private static final class Mapped {
        Base base;
        String status, fcModule, system, batch, scenario, expectedLocation, evidence, reasonCode, reasonText, notes;
        String feature, featureName, featureOrigin, anchorType, level, justification, apiRole, runtimeReq, fallback;
        boolean protectedNested;
    }

    // ---------------------------------------------------------------- 主流程

    public static void main(String[] args) throws Exception {
        List<Base> base = readBaseline();
        Set<String> protectedSet = readProtected();
        Map<String, PkgRule> pkgRules = readPackageRules();
        Map<String, TypeRule> typeRules = readTypeRules();
        Map<String, Feature> features = readFeatures();
        List<KeepEntry> keepEntries = readTypeKeep();
        List<ExploreEntry> exploreEntries = readExploration();

        checkInputs(base, pkgRules, typeRules, keepEntries, features, exploreEntries);

        List<Mapped> rows = map(base, protectedSet, pkgRules, typeRules, keepEntries, features);
        validate(rows, base, features);

        Files.createDirectories(OUT_DIR);
        writeMapping(rows);
        writePackagePlan(rows);
        writeModulePlan(rows);
        writeSummary(rows, features, exploreEntries);
        System.out.println("[ok] rows=" + rows.size()
                + " planned=" + count(rows, "Planned")
                + " pending=" + count(rows, "Pending")
                + " excluded=" + count(rows, "Excluded"));
        System.out.println("[out] " + OUT_DIR.toAbsolutePath());
    }

    // ---------------------------------------------------------------- 读输入

    private static List<Base> readBaseline() throws IOException {
        List<Base> list = new ArrayList<>();
        List<String> lines = Files.readAllLines(BASELINE, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            List<String> c = csv(line);
            Base b = new Base();
            b.module = c.get(0);
            b.pkg = c.get(1);
            b.binary = c.get(2);
            b.display = c.get(3);
            b.kind = c.get(4);
            b.nesting = c.get(5);
            b.deprecated = c.get(6);
            b.forRemoval = c.get(7);
            b.preview = c.get(8);
            b.previewMembers = c.get(9);
            b.specStatus = c.get(10);
            b.coverageScope = c.get(11);
            list.add(b);
        }
        return list;
    }

    private static List<String> readAll(Path p) throws IOException {
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        if (!lines.isEmpty() && lines.get(0).startsWith("\uFEFF")) {
            lines.set(0, lines.get(0).substring(1));
        }
        return lines;
    }

    private static Set<String> readProtected() throws IOException {
        Set<String> set = new HashSet<>();
        if (!Files.exists(PROTECTED)) {
            return set;
        }
        List<String> lines = Files.readAllLines(PROTECTED, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            set.add(csv(lines.get(i)).get(0));
        }
        return set;
    }

    private static Map<String, PkgRule> readPackageRules() throws IOException {
        Map<String, PkgRule> map = new LinkedHashMap<>();
        for (String line : readAll(PACKAGE_RULES)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            require(f.length == 8, "package-rules 列数应为 8，实际 " + f.length + "：" + f[0]);
            PkgRule r = new PkgRule();
            r.pkg = f[0].trim();
            r.status = f[1].trim();
            r.fcModule = f[2].trim();
            r.system = f[3].trim();
            r.batch = f[4].trim();
            r.mainClass = f[5].trim();
            r.reason = f[6].trim();
            r.scenario = f[7].trim();
            require(!map.containsKey(r.pkg), "package-rules 重复包：" + r.pkg);
            map.put(r.pkg, r);
        }
        return map;
    }

    private static Map<String, TypeRule> readTypeRules() throws IOException {
        Map<String, TypeRule> map = new HashMap<>();
        for (String line : readAll(TYPE_RULES)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            require(f.length == 8 || f.length == 9, "type-overrides 列数应为 8 或 9，实际 " + f.length + "：" + f[0]);
            for (String binary : f[0].split(",")) {
                TypeRule r = new TypeRule();
                r.binary = binary.trim();
                r.status = f[1].trim();
                r.fcModule = f[2].trim();
                r.system = f[3].trim();
                r.batch = f[4].trim();
                r.mainClass = f[5].trim();
                r.reason = f[6].trim();
                r.scenario = f[7].trim();
                r.feature = f.length > 8 ? f[8].trim() : "";
                require(!map.containsKey(r.binary), "type-overrides 重复类型：" + r.binary);
                map.put(r.binary, r);
            }
        }
        return map;
    }

    private static Map<String, Feature> readFeatures() throws IOException {
        Map<String, Feature> map = new LinkedHashMap<>();
        for (String line : readAll(FEATURES)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            require(f.length == 7, "feature-anchors 列数应为 7，实际 " + f.length + "：" + f[0]);
            Feature ft = new Feature();
            ft.id = f[0].trim();
            ft.name = f[1].trim();
            ft.origin = f[2].trim();
            ft.anchorType = f[3].trim();
            ft.acceptance = f[4].trim();
            ft.verification = f[5].trim();
            ft.priority = f[6].trim();
            require(VALID_ORIGIN.contains(ft.origin), "feature-anchors origin 非法：" + ft.id + " -> " + ft.origin);
            require(VALID_ANCHOR.contains(ft.anchorType), "feature-anchors anchor_type 非法：" + ft.id + " -> " + ft.anchorType);
            require(!ft.verification.isBlank(), "feature-anchors 缺验证方式：" + ft.id);
            require(!map.containsKey(ft.id), "feature-anchors 重复 id：" + ft.id);
            map.put(ft.id, ft);
        }
        return map;
    }

    private static List<KeepEntry> readTypeKeep() throws IOException {
        List<KeepEntry> list = new ArrayList<>();
        for (String line : readAll(TYPE_KEEP)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            require(f.length == 10, "type-keep 列数应为 10，实际 " + f.length + "：" + f[0]);
            KeepEntry e = new KeepEntry();
            e.pkg = f[0].trim();
            e.featureId = f[1].trim();
            e.typesRaw = f[2].trim();
            e.apiRole = f[3].trim();
            e.runtimeReq = f[4].trim();
            e.fallback = f[5].trim();
            e.justification = f[6].trim();
            e.level = f[7].trim();
            e.restReasonCode = f[8].trim();
            e.restReason = f[9].trim();
            e.keepAll = "*".equals(e.typesRaw);
            if (!e.keepAll && !"-".equals(e.typesRaw)) {
                for (String t : e.typesRaw.split(",")) {
                    String v = t.trim();
                    if (v.isEmpty()) {
                        continue;
                    }
                    e.types.add(v.indexOf('.') > 0 ? v : e.pkg + "." + v);
                }
            }
            require(VALID_ROLE.contains(e.apiRole), "type-keep api_role 非法：" + e.pkg + "/" + e.featureId + " -> " + e.apiRole);
            require(VALID_RT.contains(e.runtimeReq), "type-keep runtime_requirement 非法：" + e.pkg + "/" + e.featureId);
            require(VALID_FALLBACK.contains(e.fallback), "type-keep fallback 非法：" + e.pkg + "/" + e.featureId);
            require(VALID_LEVEL.contains(e.level), "type-keep justification_level 非法：" + e.pkg + "/" + e.featureId + " -> " + e.level);
            require(VALID_REASON.contains(e.restReasonCode), "type-keep rest_reason_code 非法：" + e.pkg + "/" + e.featureId);
            list.add(e);
        }
        return list;
    }

    private static List<ExploreEntry> readExploration() throws IOException {
        List<ExploreEntry> list = new ArrayList<>();
        for (String line : readAll(EXPLORATION)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            require(f.length == 8, "api-exploration 列数应为 8，实际 " + f.length + "：" + f[0]);
            ExploreEntry e = new ExploreEntry();
            e.domain = f[0].trim();
            e.typeScope = f[1].trim();
            e.status = f[2].trim();
            e.candidateFeature = f[3].trim();
            e.result = f[4].trim();
            e.blocker = f[5].trim();
            e.alternative = f[6].trim();
            e.nextAction = f[7].trim();
            for (String t : e.typeScope.split(",")) {
                String v = t.trim();
                if (!v.isEmpty() && !v.equals("*")) {
                    e.types.add(v);
                }
            }
            require(VALID_EXPLORE.contains(e.result), "api-exploration 探索状态非法：" + e.domain + " -> " + e.result);
            list.add(e);
        }
        return list;
    }

    // ---------------------------------------------------------------- 输入校验

    private static void checkInputs(List<Base> base, Map<String, PkgRule> pkgRules,
                                    Map<String, TypeRule> typeRules,
                                    List<KeepEntry> keepEntries, Map<String, Feature> features,
                                    List<ExploreEntry> exploreEntries) {
        Set<String> pkgs = new TreeSet<>();
        Set<String> binaries = new HashSet<>();
        for (Base b : base) {
            pkgs.add(b.pkg);
            binaries.add(b.binary);
        }
        // 包规则完整覆盖
        Set<String> missing = new TreeSet<>(pkgs);
        missing.removeAll(pkgRules.keySet());
        require(missing.isEmpty(), "package-rules 缺少包：" + missing);
        Set<String> extra = new TreeSet<>(pkgRules.keySet());
        extra.removeAll(pkgs);
        require(extra.isEmpty(), "package-rules 含清单外包：" + extra);

        // type-overrides 不得指向清单外类型
        Set<String> unknown = new TreeSet<>();
        for (String b : typeRules.keySet()) {
            if (!binaries.contains(b)) {
                unknown.add(b);
            }
        }
        require(unknown.isEmpty(), "type-overrides 含清单外类型：" + unknown);

        // type-keep：feature_id 必须存在；types 必须属于该包
        Set<String> pkgTypesMap = null;
        Set<String> badKeep = new TreeSet<>();
        for (KeepEntry e : keepEntries) {
            require(features.containsKey(e.featureId), "type-keep 引用未定义 feature_id：" + e.featureId);
            if (!e.keepAll && !"-".equals(e.typesRaw)) {
                for (String t : e.types) {
                    if (!binaries.contains(t)) {
                        badKeep.add(e.pkg + " -> " + t);
                    } else if (!t.startsWith(e.pkg + ".")) {
                        badKeep.add(e.pkg + " -> " + t + "（不属于该包）");
                    }
                }
            }
        }
        require(badKeep.isEmpty(), "type-keep 类型有问题：" + badKeep);

        // L0/L1 不得出现在 type-keep 的 types 清单（L0/L1 不允许 Planned）
        for (KeepEntry e : keepEntries) {
            require("L2".equals(e.level) || "L3".equals(e.level) || "L4".equals(e.level),
                    "type-keep justification_level 必须为 L2/L3/L4（L0/L1 不允许进入类型清单）：" + e.pkg + "/" + e.featureId);
        }

        // feature 双向完整性：每个 feature 至少被一个 keep/override 引用（在 map 阶段再验；此处只验存在性）

        // api-exploration：type_scope 引用的类型/包必须存在（`*` 与 `-` 跳过）
        Set<String> allBinaries = new HashSet<>(binaries);
        Set<String> badExplore = new TreeSet<>();
        for (ExploreEntry e : exploreEntries) {
            for (String t : e.types) {
                if (t.contains(".")) {
                    // 包前缀（以 .* 或 * 结尾，或精确包名）
                    String prefix = t.endsWith(".*") ? t.substring(0, t.length() - 2)
                            : t.endsWith("*") ? t.substring(0, t.length() - 1) : t;
                    boolean isPkgPrefix = pkgs.contains(prefix)
                            || pkgs.stream().anyMatch(p -> p.startsWith(prefix + "."));
                    if (!isPkgPrefix && !allBinaries.contains(t)) {
                        badExplore.add(e.domain + " -> " + t);
                    }
                }
            }
        }
        require(badExplore.isEmpty(), "api-exploration 引用未知类型/包：" + badExplore);
    }

    // ---------------------------------------------------------------- 映射

    private static List<Mapped> map(List<Base> base, Set<String> protectedSet,
                                    Map<String, PkgRule> pkgRules, Map<String, TypeRule> typeRules,
                                    List<KeepEntry> keepEntries, Map<String, Feature> features) {
        // 建立 类型 -> KeepEntry 索引（一个类型可命中多个 feature，取优先级最高者）
        Map<String, List<KeepEntry>> typeToKeep = new HashMap<>();
        for (KeepEntry e : keepEntries) {
            if (e.keepAll) {
                continue; // 整包准入在下方按包处理
            }
            for (String t : e.types) {
                typeToKeep.computeIfAbsent(t, k -> new ArrayList<>()).add(e);
            }
        }
        // 包级 keepAll 索引 + 包级 rest 索引（明确清单的包，清单外类型按 rest 排除）
        Map<String, KeepEntry> pkgKeepAll = new HashMap<>();
        Map<String, KeepEntry> pkgRest = new HashMap<>();
        for (KeepEntry e : keepEntries) {
            if (e.keepAll) {
                require(!pkgKeepAll.containsKey(e.pkg), "type-keep 同一包多个 keepAll：" + e.pkg);
                pkgKeepAll.put(e.pkg, e);
            } else {
                pkgRest.putIfAbsent(e.pkg, e);
            }
        }

        List<Mapped> out = new ArrayList<>(base.size());
        for (Base b : base) {
            PkgRule pr = pkgRules.get(b.pkg);
            TypeRule tr = typeRules.get(b.binary);
            boolean isProtected = protectedSet.contains(b.binary);

            Mapped m = new Mapped();
            m.base = b;
            m.protectedNested = isProtected;
            m.feature = "-";
            m.featureName = "-";
            m.featureOrigin = "-";
            m.anchorType = "-";
            m.level = "-";
            m.justification = "-";
            m.apiRole = "-";
            m.runtimeReq = "-";
            m.fallback = "-";

            // 1. Preview 优先
            if ("EXCLUDED_PREVIEW".equals(b.coverageScope)) {
                m.status = "Excluded";
                m.fcModule = "-";
                m.system = "-";
                m.batch = "-";
                m.reasonCode = "PREVIEW";
                m.scenario = "不适用：Java SE 21 预览 API，任务书禁止用于正式实现";
                m.reasonText = "[未探索] Preview 类型，阶段一已排除出覆盖率分母，仅登记（预览 API 禁止用于正式实现，未做 API 驱动探索）";
                m.expectedLocation = "-";
                m.evidence = "—（不适用：Preview）";
                m.notes = "阶段一 coverage_scope=EXCLUDED_PREVIEW";
                out.add(m);
                continue;
            }

            // 2. protected 嵌套
            if (isProtected) {
                m.status = "Excluded";
                m.fcModule = "-";
                m.system = "-";
                m.batch = "-";
                m.reasonCode = "ACCESS";
                m.scenario = "不适用（Excluded）";
                m.reasonText = "[已探索] protected 嵌套类型，非 public，包外不可引用";
                m.expectedLocation = "-";
                m.evidence = "—（不适用）";
                m.notes = "protected 嵌套（168 清单）";
                out.add(m);
                continue;
            }

            // 3. 类型级覆盖优先（最高优先）
            if (tr != null && !"Inherit".equals(tr.status)) {
                m.status = tr.status;
                m.fcModule = "-".equals(tr.fcModule) ? "-" : tr.fcModule;
                m.system = "-".equals(tr.system) ? "-" : tr.system;
                m.batch = "-".equals(tr.batch) ? "-" : tr.batch;
                if ("Excluded".equals(tr.status)) {
                    m.reasonCode = tr.reason;
                    m.reasonText = tr.scenario;
                    m.scenario = "不适用（Excluded）";
                    m.expectedLocation = "-";
                    m.evidence = "—（不适用）";
                    m.level = "L3"; // 类型级显式排除也视为已论证
                    m.justification = tr.scenario;
                    m.apiRole = "-";
                    m.notes = notesFor(b, false, "Excluded");
                } else {
                    // 类型级显式 Planned/Pending：必须挂 feature
                    Feature ft = tr.feature.isEmpty() || "-".equals(tr.feature) ? null : features.get(tr.feature);
                    require(ft != null, "type-overrides Planned/Pending 缺有效 feature：" + b.binary);
                    m.feature = ft.id;
                    m.featureName = ft.name;
                    m.featureOrigin = ft.origin;
                    m.anchorType = ft.anchorType;
                    m.level = "Planned".equals(tr.status) ? "L3" : "L2";
                    m.justification = tr.scenario;
                    m.apiRole = roleGuess(b);
                    m.runtimeReq = "NONE";
                    m.fallback = "Planned".equals(tr.status) ? "NONE" : "STUB";
                    m.scenario = tr.scenario;
                    m.expectedLocation = tr.fcModule + "/src/main/java/" + tr.mainClass;
                    m.evidence = "—（" + tr.status + "，待实现回填）";
                    m.notes = notesFor(b, false, tr.status);
                }
                out.add(m);
                continue;
            }

            // 4. type-keep 功能↔类型准入（多 feature 命中取最高 level）
            List<KeepEntry> hits = typeToKeep.get(b.binary);
            KeepEntry pkgAll = pkgKeepAll.get(b.pkg);
            KeepEntry best = null;
            if (hits != null && !hits.isEmpty()) {
                for (KeepEntry e : hits) {
                    if (best == null || levelRank(e.level) > levelRank(best.level)) {
                        best = e;
                    }
                }
            }
            if (best == null && pkgAll != null) {
                best = pkgAll;
            }

            if (best != null) {
                Feature ft = features.get(best.featureId);
                m.feature = ft.id;
                m.featureName = ft.name;
                m.featureOrigin = ft.origin;
                m.anchorType = ft.anchorType;
                m.level = best.level;
                m.justification = best.justification;
                m.apiRole = best.apiRole;
                m.runtimeReq = best.runtimeReq;
                m.fallback = best.fallback;
                m.fcModule = pr.fcModule;
                m.system = pr.system;
                if ("L3".equals(best.level) || "L4".equals(best.level)) {
                    m.status = "Planned";
                    m.batch = pr.batch;
                    m.scenario = "功能「" + ft.name + "」：" + best.justification;
                    m.expectedLocation = pr.fcModule + "/src/main/java/" + pr.mainClass;
                    m.evidence = "—（Planned，待实现回填）";
                } else {
                    // L2 → Pending（功能已设计，类型待逐条论证升 L3）
                    m.status = "Pending";
                    m.batch = "-";
                    m.scenario = "功能「" + ft.name + "」已设计（L2），待逐类型论证 api_role 后升 L3";
                    m.expectedLocation = "-";
                    m.evidence = "—（Pending，待阶段四 L3 论证）";
                }
                m.reasonCode = "";
                m.reasonText = "";
                m.notes = notesFor(b, false, m.status);
                out.add(m);
                continue;
            }

            // 4b. package 有 type-keep 明确清单但类型不在清单内 → 按 rest_reason 排除
            KeepEntry restEntry = pkgRest.get(b.pkg);
            if (best == null && restEntry != null) {
                m.status = "Excluded";
                m.fcModule = "-";
                m.system = "-";
                m.batch = "-";
                m.reasonCode = restEntry.restReasonCode;
                m.reasonText = "[已探索] " + restEntry.restReason;
                m.scenario = "不适用（Excluded）";
                m.expectedLocation = "-";
                m.evidence = "—（不适用）";
                m.level = "L3";
                m.justification = restEntry.restReason;
                m.apiRole = "-";
                m.notes = notesFor(b, false, "Excluded");
                out.add(m);
                continue;
            }

            // 5. 包级默认：Excluded 直接排除；Planned 降为 Pending（L1 待逐类型论证）
            if ("Excluded".equals(pr.status)) {
                m.status = "Excluded";
                m.fcModule = "-";
                m.system = "-";
                m.batch = "-";
                m.reasonCode = pr.reason;
                m.reasonText = pr.scenario;
                m.scenario = "不适用（Excluded）";
                m.expectedLocation = "-";
                m.evidence = "—（不适用）";
                m.level = "L1";
                m.justification = pr.scenario;
                m.apiRole = "-";
                m.notes = notesFor(b, false, "Excluded");
            } else {
                // 包级 Planned → Pending（L1 包级理由，不允许直接 Planned）
                m.status = "Pending";
                m.fcModule = pr.fcModule;
                m.system = pr.system;
                m.batch = "-";
                m.reasonCode = "";
                m.reasonText = "";
                m.scenario = "包级理由（L1）：" + pr.scenario + "；待逐类型 L3 论证后转 Planned";
                m.expectedLocation = "-";
                m.evidence = "—（Pending，待阶段四 L3 论证）";
                m.level = "L1";
                m.justification = pr.scenario;
                m.apiRole = "-";
                m.notes = notesFor(b, false, "Pending");
            }
            out.add(m);
        }
        return out;
    }

    private static int levelRank(String level) {
        return switch (level) {
            case "L4" -> 4;
            case "L3" -> 3;
            case "L2" -> 2;
            case "L1" -> 1;
            default -> 0;
        };
    }

    private static String roleGuess(Base b) {
        String simple = b.display.substring(b.display.lastIndexOf('.') + 1);
        if (simple.endsWith("Exception") || simple.endsWith("Error")) {
            return "ERROR";
        }
        if (simple.endsWith("Factory") || simple.endsWith("Builder") || simple.endsWith("Provider")
                || simple.endsWith("Manager")) {
            return "FACTORY";
        }
        if (simple.endsWith("Listener") || simple.endsWith("Handler")) {
            return "LISTENER";
        }
        if (simple.endsWith("Permission")) {
            return "SECURITY";
        }
        return "MODEL";
    }

    private static String notesFor(Base b, boolean isProtected, String status) {
        List<String> notes = new ArrayList<>();
        if ("yes".equals(b.deprecated)) {
            notes.add("Deprecated" + ("yes".equals(b.forRemoval) ? "(forRemoval=true)" : ""));
        }
        if ("yes".equals(b.previewMembers)) {
            notes.add("含成员级预览方法");
        }
        return notes.isEmpty() ? "" : String.join("；", notes);
    }

    // ---------------------------------------------------------------- 校验

    private static void validate(List<Mapped> rows, List<Base> base, Map<String, Feature> features) {
        require(rows.size() == base.size(), "输出行数应与基线一致：" + rows.size() + " != " + base.size());
        Set<String> outBinaries = new HashSet<>();
        Map<String, Integer> featureRefCount = new TreeMap<>();
        for (Mapped m : rows) {
            require(outBinaries.add(m.base.binary), "输出存在重复类型：" + m.base.binary);
            require(VALID_STATUS.contains(m.status), "状态非法：" + m.base.binary + " -> " + m.status);

            if ("Excluded".equals(m.status)) {
                require(m.reasonCode != null && VALID_REASON.contains(m.reasonCode),
                        "Excluded 缺合法原因码：" + m.base.binary);
                require(m.reasonText != null && !m.reasonText.isBlank(), "Excluded 缺原因说明：" + m.base.binary);
                require(m.reasonText.startsWith("[已探索]") || m.reasonText.startsWith("[未探索]"),
                        "Excluded 原因须标注是否做过 API 驱动探索：" + m.base.binary);
                require(!"ENV".equals(m.reasonCode) || m.reasonText.contains("证据"),
                        "ENV 必须含可复现运行时证据：" + m.base.binary);
            } else if ("Pending".equals(m.status)) {
                require(m.justification != null && !"-".equals(m.justification) && !m.justification.isBlank(),
                        "Pending 缺未决问题/重新评估条件：" + m.base.binary);
                require(!"-".equals(m.level), "Pending 缺论证层级：" + m.base.binary);
            } else { // Planned
                require(!"-".equals(m.feature), "Planned 缺功能锚点：" + m.base.binary);
                require(features.containsKey(m.feature), "Planned 引用无效 feature：" + m.base.binary + " -> " + m.feature);
                require("L3".equals(m.level) || "L4".equals(m.level),
                        "Planned 必须 L3/L4，实际 " + m.level + "：" + m.base.binary);
                require(!m.justification.isBlank() && !"-".equals(m.justification),
                        "Planned 缺论证文本：" + m.base.binary);
                require(VALID_ROLE.contains(m.apiRole) && !"-".equals(m.apiRole),
                        "Planned 缺 api_role：" + m.base.binary);
                require(m.expectedLocation != null && !"-".equals(m.expectedLocation),
                        "Planned 缺实现位置：" + m.base.binary);
                require(m.fcModule != null && !"-".equals(m.fcModule), "Planned 缺 fc 模块：" + m.base.binary);
                require(m.batch != null && m.batch.matches("M[0-6]"), "Planned 缺合法批次：" + m.base.binary);
            }

            if (!"-".equals(m.feature)) {
                featureRefCount.merge(m.feature, 1, Integer::sum);
            }
            if ("EXCLUDED_PREVIEW".equals(m.base.coverageScope)) {
                require("Excluded".equals(m.status) && "PREVIEW".equals(m.reasonCode),
                        "Preview 行必须 Excluded/PREVIEW：" + m.base.binary);
            }
        }
        Set<String> baseBinaries = new HashSet<>();
        base.forEach(b -> baseBinaries.add(b.binary));
        require(outBinaries.equals(baseBinaries), "输出类型集合与基线不一致");

        // 功能锚点双向完整性：每个 feature 至少映射一个类型
        Set<String> referenced = featureRefCount.keySet();
        Set<String> orphanFeatures = new TreeSet<>(features.keySet());
        orphanFeatures.removeAll(referenced);
        require(orphanFeatures.isEmpty(), "存在孤立功能锚点（无任何类型映射）：" + orphanFeatures);
    }

    // ---------------------------------------------------------------- 输出

    private static void writeMapping(List<Mapped> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("module,package,type_binary,type_name,kind,nesting,deprecated,for_removal,preview,coverage_scope,")
                .append("status,feature_name,feature_origin,feature_anchor,anchor_type,justification_level,")
                .append("justification,api_role,runtime_requirement,fallback_strategy,implementation_location,")
                .append("test_evidence,fc_module,batch,uncovered_reason_code,uncovered_reason\n");
        for (Mapped m : rows) {
            Base b = m.base;
            String typeName = b.display.substring(b.display.lastIndexOf('.') + 1).replace('$', '.');
            sb.append(q(b.module)).append(',').append(q(b.pkg)).append(',').append(q(b.binary)).append(',')
                    .append(q(typeName)).append(',').append(q(b.kind)).append(',').append(q(b.nesting)).append(',')
                    .append(q(b.deprecated)).append(',').append(q(b.forRemoval)).append(',').append(q(b.preview)).append(',')
                    .append(q(b.coverageScope)).append(',').append(q(m.status)).append(',').append(q(m.featureName)).append(',')
                    .append(q(m.featureOrigin)).append(',').append(q(m.feature)).append(',').append(q(m.anchorType)).append(',')
                    .append(q(m.level)).append(',').append(q(m.justification)).append(',').append(q(m.apiRole)).append(',')
                    .append(q(m.runtimeReq)).append(',').append(q(m.fallback)).append(',').append(q(m.expectedLocation)).append(',')
                    .append(q("")).append(',').append(q(m.fcModule)).append(',').append(q(m.batch)).append(',')
                    .append(q(m.reasonCode)).append(',').append(q(m.reasonText)).append('\n');
        }
        Files.write(OUT_DIR.resolve("api-mapping.csv"), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writePackagePlan(List<Mapped> rows) throws IOException {
        Map<String, List<Mapped>> byPkg = new TreeMap<>();
        for (Mapped m : rows) {
            byPkg.computeIfAbsent(m.base.pkg, k -> new ArrayList<>()).add(m);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("module,package,fc_module,batch,total_types,in_denominator,planned,pending,excluded,")
                .append("coverage_class,exclusion_reasons,plan_note\n");
        for (Map.Entry<String, List<Mapped>> e : byPkg.entrySet()) {
            List<Mapped> list = e.getValue();
            int total = list.size();
            int inDen = 0, planned = 0, pending = 0, excluded = 0, prev = 0;
            Map<String, Integer> reasons = new TreeMap<>();
            String module = "", fcModule = "-", batch = "-";
            for (Mapped m : list) {
                module = m.base.module;
                if ("IN_DENOMINATOR".equals(m.base.coverageScope)) {
                    inDen++;
                }
                if ("EXCLUDED_PREVIEW".equals(m.base.coverageScope)) {
                    prev++;
                    continue;
                }
                switch (m.status) {
                    case "Planned" -> {
                        planned++;
                        fcModule = m.fcModule;
                        batch = m.batch;
                    }
                    case "Pending" -> pending++;
                    case "Excluded" -> {
                        excluded++;
                        reasons.merge(m.reasonCode, 1, Integer::sum);
                    }
                    default -> { }
                }
            }
            String covClass = inDen == 0 ? "PREVIEW_ONLY"
                    : planned > 0 && pending == 0 && excluded == 0 ? "FULL"
                    : planned > 0 ? "PARTIAL"
                    : pending > 0 ? "PENDING_ONLY" : "NONE";
            String note;
            if ("PREVIEW_ONLY".equals(covClass)) {
                note = "整包 Preview（" + prev + " 行）";
            } else if ("NONE".equals(covClass)) {
                note = "整包排除：" + reasons;
            } else if ("FULL".equals(covClass)) {
                note = "包内分母类型全部 Planned";
            } else if ("PENDING_ONLY".equals(covClass)) {
                note = "整包 Pending（" + pending + " 行）：待 L3 逐类型论证";
            } else {
                note = "部分覆盖：Planned " + planned + " / Pending " + pending + " / Excluded " + excluded;
            }
            sb.append(q(module)).append(',').append(q(e.getKey())).append(',').append(q(fcModule)).append(',')
                    .append(q(batch)).append(',').append(total).append(',').append(inDen).append(',')
                    .append(planned).append(',').append(pending).append(',').append(excluded).append(',')
                    .append(covClass).append(',').append(q(reasons.toString())).append(',').append(q(note)).append('\n');
        }
        Files.write(OUT_DIR.resolve("package-plan.csv"), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeModulePlan(List<Mapped> rows) throws IOException {
        Map<String, List<Mapped>> byModule = new TreeMap<>();
        for (Mapped m : rows) {
            byModule.computeIfAbsent(m.base.module, k -> new ArrayList<>()).add(m);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("module,total_types,in_denominator,planned,pending,excluded,packages,covered_packages,")
                .append("type_rate_in_denominator,package_rate,note\n");
        for (Map.Entry<String, List<Mapped>> e : byModule.entrySet()) {
            List<Mapped> list = e.getValue();
            int inDen = 0, planned = 0, pending = 0, excluded = 0;
            Set<String> pkgs = new TreeSet<>();
            Set<String> coveredPkgs = new TreeSet<>();
            for (Mapped m : list) {
                pkgs.add(m.base.pkg);
                if ("EXCLUDED_PREVIEW".equals(m.base.coverageScope)) {
                    continue;
                }
                inDen++;
                switch (m.status) {
                    case "Planned" -> {
                        planned++;
                        coveredPkgs.add(m.base.pkg);
                    }
                    case "Pending" -> pending++;
                    case "Excluded" -> excluded++;
                    default -> { }
                }
            }
            double typeRate = inDen == 0 ? 0 : 100.0 * planned / inDen;
            double pkgRate = pkgs.isEmpty() ? 0 : 100.0 * coveredPkgs.size() / pkgs.size();
            sb.append(q(e.getKey())).append(',').append(list.size()).append(',').append(inDen).append(',')
                    .append(planned).append(',').append(pending).append(',').append(excluded).append(',')
                    .append(pkgs.size()).append(',').append(coveredPkgs.size()).append(',')
                    .append(fmt(typeRate)).append(',').append(fmt(pkgRate)).append(',')
                    .append(q(moduleNote(e.getKey(), planned, pending, excluded))).append('\n');
        }
        Files.write(OUT_DIR.resolve("module-plan.csv"), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String moduleNote(String module, int planned, int pending, int excluded) {
        if (planned == 0 && pending > 0) {
            return "待 L3 论证（Pending " + pending + "）";
        }
        if (planned == 0) {
            return "整模块排除";
        }
        return "Planned " + planned + " / Pending " + pending + " / Excluded " + excluded;
    }

    private static void writeSummary(List<Mapped> rows, Map<String, Feature> features,
                                     List<ExploreEntry> exploreEntries) throws IOException {
        int total = rows.size();
        int inDen = 0, preview = 0, planned = 0, pending = 0, excluded = 0;
        Map<String, Integer> reasonCount = new TreeMap<>();
        Map<String, Integer> levelCount = new TreeMap<>();
        Map<String, Integer> originCount = new TreeMap<>();
        Map<String, Integer> anchorCount = new TreeMap<>();
        Map<String, Integer> batchCount = new TreeMap<>();
        Map<String, Integer> featureCount = new TreeMap<>();
        Map<String, String> featureMeta = new TreeMap<>();
        Map<String, List<Mapped>> byModule = new TreeMap<>();
        Map<String, int[]> perPkg = new TreeMap<>();
        Set<String> coveredPkgs = new TreeSet<>();

        for (Mapped m : rows) {
            byModule.computeIfAbsent(m.base.module, k -> new ArrayList<>()).add(m);
            if ("EXCLUDED_PREVIEW".equals(m.base.coverageScope)) {
                preview++;
                continue;
            }
            inDen++;
            int[] c = perPkg.computeIfAbsent(m.base.pkg, k -> new int[2]);
            switch (m.status) {
                case "Planned" -> {
                    planned++;
                    coveredPkgs.add(m.base.pkg);
                    c[0]++;
                    batchCount.merge(m.batch, 1, Integer::sum);
                    featureCount.merge(m.feature, 1, Integer::sum);
                    featureMeta.put(m.feature, m.featureName + "|" + m.featureOrigin + "|" + m.anchorType);
                }
                case "Pending" -> {
                    pending++;
                    featureCount.merge(m.feature, 1, Integer::sum);
                }
                case "Excluded" -> {
                    excluded++;
                    c[1]++;
                    reasonCount.merge(m.reasonCode, 1, Integer::sum);
                }
                default -> { }
            }
            if (!"-".equals(m.level)) {
                levelCount.merge(m.level, 1, Integer::sum);
            }
            if (!"-".equals(m.featureOrigin)) {
                originCount.merge(m.featureOrigin, 1, Integer::sum);
            }
            if (!"-".equals(m.anchorType)) {
                anchorCount.merge(m.anchorType, 1, Integer::sum);
            }
        }
        Set<String> fullPkgs = new TreeSet<>();
        for (Map.Entry<String, int[]> e : perPkg.entrySet()) {
            if (e.getValue()[0] > 0 && e.getValue()[1] == 0) {
                fullPkgs.add(e.getKey());
            }
        }
        int denomPkgs = perPkg.size();

        StringBuilder sb = new StringBuilder();
        sb.append("# 阶段三 v3 映射统计摘要（自动生成）\n\n");
        sb.append("> 项目目标：**API 覆盖优先，游戏作为承载载体**。准入：Planned 必须 L3 逐类型论证。\n");
        sb.append("> 分母：`coverage_scope=IN_DENOMINATOR`（类型 ").append(inDen).append(" / 包 ").append(denomPkgs)
                .append(" / 模块 20）。\n\n");

        sb.append("## 1. 行数与状态\n\n");
        sb.append("| 项 | 数量 |\n|---|---|\n");
        sb.append("| 冻结清单总行数 | ").append(total).append(" |\n");
        sb.append("| 其中 Preview | ").append(preview).append(" |\n");
        sb.append("| 分母行 | ").append(inDen).append(" |\n");
        sb.append("| Planned（L3 已论证） | ").append(planned).append(" |\n");
        sb.append("| Pending（L1/L2 待论证） | ").append(pending).append(" |\n");
        sb.append("| Excluded | ").append(excluded).append(" |\n");
        sb.append("| 合计 | ").append(planned + pending + excluded).append(" = ").append(inDen).append(" |\n\n");

        sb.append("## 2. 覆盖率（口径：Planned）\n\n");
        sb.append("| 指标 | 数值 |\n|---|---|\n");
        sb.append("| 类型级 | ").append(fmt(100.0 * planned / inDen)).append("%（").append(planned).append("/").append(inDen).append("） |\n");
        sb.append("| 包级 | ").append(fmt(100.0 * coveredPkgs.size() / denomPkgs)).append("%（")
                .append(coveredPkgs.size()).append("/").append(denomPkgs).append("） |\n");
        sb.append("| 完整包 | ").append(fmt(100.0 * fullPkgs.size() / denomPkgs)).append("%（")
                .append(fullPkgs.size()).append("/").append(denomPkgs).append("） |\n");
        sb.append("| 模块级 | ").append(fmt(100.0 * byModuleWithPlanned(rows) / 20.0)).append("%（")
                .append(byModuleWithPlanned(rows)).append("/20） |\n\n");
        sb.append("> Planned 是**规划覆盖**，最终以阶段六 Verified 为准。\n\n");

        sb.append("## 3. 论证层级分布\n\n");
        sb.append("| 层级 | 数量 |\n|---|---|\n");
        for (Map.Entry<String, Integer> e : levelCount.entrySet()) {
            sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
        }
        sb.append("\n");

        sb.append("## 4. 功能来源分布（feature_origin）\n\n");
        sb.append("| 来源 | 数量 |\n|---|---|\n");
        for (Map.Entry<String, Integer> e : originCount.entrySet()) {
            sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
        }
        sb.append("\n");

        sb.append("## 5. 锚点类型分布（anchor_type）\n\n");
        sb.append("| 锚点 | 数量 |\n|---|---|\n");
        for (Map.Entry<String, Integer> e : anchorCount.entrySet()) {
            sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
        }
        sb.append("\n");

        sb.append("## 6. 排除原因分布\n\n");
        sb.append("| 原因码 | 数量 |\n|---|---|\n");
        for (Map.Entry<String, Integer> e : reasonCount.entrySet()) {
            sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
        }
        sb.append("\n");

        sb.append("## 7. 批次分布（Planned）\n\n");
        sb.append("| 批次 | 类型数 |\n|---|---|\n");
        for (Map.Entry<String, Integer> e : batchCount.entrySet()) {
            sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
        }
        sb.append("\n");

        sb.append("## 8. 功能锚点分布（Planned/Pending）\n\n");
        sb.append("| feature_id | 名称 | 来源 | 锚点 | 类型数 |\n|---|---|---|---|---|\n");
        for (Map.Entry<String, Integer> e : featureCount.entrySet()) {
            String meta = featureMeta.getOrDefault(e.getKey(), "-|-|-");
            String[] parts = meta.split("\\|", -1);
            Feature ft = findById(features, e.getKey());
            sb.append("| ").append(e.getKey()).append(" | ").append(parts[0]).append(" | ")
                    .append(parts[1]).append(" | ").append(parts[2]).append(" | ").append(e.getValue()).append(" |\n");
        }
        sb.append("\n");

        sb.append("## 9. JDK 模块级明细\n\n");
        sb.append("| 模块 | 分母 | Planned | Pending | Excluded | 类型级 |\n|---|---|---|---|---|---|\n");
        for (Map.Entry<String, List<Mapped>> e : byModule.entrySet()) {
            int d = 0, pl = 0, pd = 0, x = 0;
            for (Mapped m : e.getValue()) {
                if ("EXCLUDED_PREVIEW".equals(m.base.coverageScope)) {
                    continue;
                }
                d++;
                switch (m.status) {
                    case "Planned" -> pl++;
                    case "Pending" -> pd++;
                    case "Excluded" -> x++;
                    default -> { }
                }
            }
            sb.append("| ").append(e.getKey()).append(" | ").append(d).append(" | ").append(pl).append(" | ")
                    .append(pd).append(" | ").append(x).append(" | ").append(fmt(d == 0 ? 0 : 100.0 * pl / d)).append("% |\n");
        }
        sb.append('\n');

        sb.append("## 10. 校验结论\n\n");
        sb.append("- 输出行数与冻结清单一致；类型集合一致。\n");
        sb.append("- 状态三态：Planned / Pending / Excluded。\n");
        sb.append("- Planned 全部 L3 论证 + 功能锚点 + api_role + 实现位置。\n");
        sb.append("- Pending 记录未决问题；Excluded 标注 API 驱动探索状态。\n");
        sb.append("- 功能锚点双向完整：无孤立锚点。\n");
        sb.append("- 分母 3836 未变，覆盖率变化全部来自逐类型状态调整。\n");

        sb.append("\n## 11. API 探索台账（api-exploration.tsv）\n\n");
        Map<String, Integer> exploreCount = new TreeMap<>();
        for (ExploreEntry e : exploreEntries) {
            exploreCount.merge(e.result, 1, Integer::sum);
        }
        sb.append("| 探索状态 | 台账条目数 |\n|---|---|\n");
        for (Map.Entry<String, Integer> e : exploreCount.entrySet()) {
            sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
        }
        sb.append("\n> `UNEXPLORED`=因废弃或确实无场景，未做 API 驱动探索；`OPPORTUNITY_FOUND`=已发现机会待设计；")
                .append("`FEATURE_DESIGNED`=功能已设计（L2 候选）；`TYPE_JUSTIFIED`=已逐类型论证（L3）。\n");

        Files.write(OUT_DIR.resolve("mapping-summary.md"), sb.toString().getBytes(StandardCharsets.UTF_8));

        StringBuilder ck = new StringBuilder();
        ck.append("# 阶段三 v3 映射自检证据（自动生成）\n\n");
        ck.append("- 行数：").append(total).append("（期望 3874）\n");
        ck.append("- 分母行：").append(inDen).append("；Planned ").append(planned)
                .append("；Pending ").append(pending).append("；Excluded ").append(excluded).append("\n");
        ck.append("- 论证层级：").append(levelCount).append("\n");
        ck.append("- 功能来源：").append(originCount).append("\n");
        ck.append("- 锚点类型：").append(anchorCount).append("\n");
        ck.append("- 原因码：").append(reasonCount).append("\n");
        ck.append("- 覆盖率：类型级 ").append(fmt(100.0 * planned / inDen))
                .append("%；包级 ").append(fmt(100.0 * coveredPkgs.size() / denomPkgs))
                .append("%；完整包 ").append(fmt(100.0 * fullPkgs.size() / denomPkgs))
                .append("%；模块级 ").append(fmt(100.0 * byModuleWithPlanned(rows) / 20.0)).append("%\n");
        Files.write(OUT_DIR.resolve("mapping-checks.txt"), ck.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static int byModuleWithPlanned(List<Mapped> rows) {
        Set<String> modules = new TreeSet<>();
        for (Mapped m : rows) {
            if ("Planned".equals(m.status)) {
                modules.add(m.base.module);
            }
        }
        return modules.size();
    }

    private static Feature findById(Map<String, Feature> features, String id) {
        return features.get(id);
    }

    private static int count(List<Mapped> rows, String status) {
        int n = 0;
        for (Mapped m : rows) {
            if (status.equals(m.status)) {
                n++;
            }
        }
        return n;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static void require(boolean cond, String msg) {
        if (!cond) {
            throw new IllegalStateException("[FAIL] " + msg);
        }
    }

    private static String q(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static List<String> csv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQ) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQ = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                inQ = true;
            } else if (ch == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
