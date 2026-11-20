import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 阶段一正式冻结工具（零第三方依赖，JDK 21 单文件运行）。
 *
 * 输入（只读，不修改任何既有基准数据）：
 *   docs/phase1/data/api-baseline.csv                 11 列，3888 行（含表头）
 *   docs/phase1/data/verification/closure-missing.csv 1 列，169 行（含表头）168 个 protected 嵌套
 *
 * 输出（冻结交付物）：
 *   coverage-baseline.csv          正式冻结清单：SE 口径 3874 行 + coverage_scope 列
 *                                  IN_DENOMINATOR=3836（覆盖率分母）/ EXCLUDED_PREVIEW=38
 *   extension-smartcardio.csv      13 行（JAVA_PREFIX_ONLY，官方 API 文档分组收录、非 JSR 396 §7）
 *   extension-preview.csv          38 行（类型级 Preview，单独登记，不入分母）
 *   extension-protected-nested.csv 168 行（javadoc 文档化的 protected 嵌套，非 public 类型）
 *   freeze-manifest.md             冻结边界、口径层次、文件 SHA-256、阶段三使用约定
 *
 * 用法： java tools/FreezeBaseline.java [dataDir]   （默认 docs/phase1/data）
 *
 * 冻结校验常量（2026-11-20 用户裁决）：任何一项不符即抛错，防止数据变更后静默产出错误冻结清单。
 */
public class FreezeBaseline {

    // ---- 冻结校验常量 ----
    private static final int EXPECT_TOTAL_ROWS   = 3887; // 不含表头
    private static final int EXPECT_SE_TYPES     = 3874; // 正式冻结的基准清单边界
    private static final int EXPECT_SMARTCARDIO  = 13;
    private static final int EXPECT_PREVIEW      = 38;
    private static final int EXPECT_DENOMINATOR  = 3836; // 3874 - 38
    private static final int EXPECT_DENOM_PKGS   = 176;  // 177 - java.lang.foreign（整包纯 Preview）
    private static final int EXPECT_PROTECTED    = 168;

    private static final String FREEZE_DATE = "2026-11-20";
    private static final String HEADER_11 =
            "module,package,type_binary,type_display,kind,nesting,deprecated,for_removal,"
                    + "preview,preview_members,spec_status";

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "docs/phase1/data");
        Path baseline = dir.resolve("api-baseline.csv");
        Path closureMissing = dir.resolve("verification/closure-missing.csv");

        List<String> lines = Files.readAllLines(baseline, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !HEADER_11.equals(lines.get(0))) {
            throw new IllegalStateException("api-baseline.csv 表头不符合预期: " + lines.get(0));
        }
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.isBlank()) continue;
            String[] c = l.split(",", -1);
            if (c.length != 11) throw new IllegalStateException("列数异常 line " + (i + 1) + ": " + l);
            rows.add(c);
        }
        if (rows.size() != EXPECT_TOTAL_ROWS) {
            throw new IllegalStateException("类型总数 " + rows.size() + " != " + EXPECT_TOTAL_ROWS);
        }

        List<String[]> frozen = new ArrayList<>();   // SE 3874 行
        List<String[]> smartcardio = new ArrayList<>();
        List<String[]> preview = new ArrayList<>();
        Set<String> denomPkgs = new LinkedHashSet<>();
        int nSe = 0, nSmart = 0, nPreview = 0, nDenom = 0, nDeprecated = 0, nForRemoval = 0;

        for (String[] c : rows) {
            String module = c[0], pkg = c[1], binary = c[2];
            String deprecated = c[6], forRemoval = c[7], isPreview = c[8], spec = c[10];
            if (!"SE".equals(spec) && !"JAVA_PREFIX_ONLY".equals(spec)) {
                throw new IllegalStateException("未知 spec_status: " + spec + " @ " + binary);
            }
            if ("JAVA_PREFIX_ONLY".equals(spec)) {
                nSmart++;
                smartcardio.add(withExtra(c,
                        "EXTENSION_SMARTCARDIO",
                        "官方 Java SE API 文档按 java.* 前缀分组收录；不属于 JSR 396 §7 标准模块集合"));
                continue;
            }
            nSe++;
            String scope, reason;
            if ("yes".equals(isPreview)) {
                nPreview++;
                scope = "EXCLUDED_PREVIEW";
                reason = "Java SE 21 类型级 Preview API；任务书 §3.3 要求不得混入正式覆盖率分母，单独登记";
                preview.add(withExtra(c, "EXCLUDED_PREVIEW", reason));
            } else {
                nDenom++;
                denomPkgs.add(pkg);
                scope = "IN_DENOMINATOR";
                reason = "";
            }
            if ("yes".equals(deprecated)) nDeprecated++;
            if ("yes".equals(forRemoval)) nForRemoval++;
            frozen.add(withScope(c, scope, reason));
        }

        // ---- protected 嵌套扩展清单 ----
        List<String> protectedRows = Files.readAllLines(closureMissing, StandardCharsets.UTF_8);
        List<String[]> prot = new ArrayList<>();
        for (int i = 1; i < protectedRows.size(); i++) {
            String b = protectedRows.get(i).trim();
            if (b.isEmpty()) continue;
            prot.add(new String[] { b,
                    "javadoc 文档化的 protected 嵌套类型（或外围链含 protected）；非 public 公开类型，不入分母" });
        }

        // ---- 冻结校验（全部硬断言）----
        check("SE 类型", nSe, EXPECT_SE_TYPES);
        check("smartcardio 扩展", nSmart, EXPECT_SMARTCARDIO);
        check("Preview 扩展", nPreview, EXPECT_PREVIEW);
        check("覆盖率分母类型", nDenom, EXPECT_DENOMINATOR);
        check("覆盖率分母包数", denomPkgs.size(), EXPECT_DENOM_PKGS);
        check("protected 扩展", prot.size(), EXPECT_PROTECTED);
        if (nDeprecated != 68 || nForRemoval != 36) {
            throw new IllegalStateException("Deprecated/forRemoval 与冻结预期不符: " + nDeprecated + "/" + nForRemoval);
        }
        if (nSe + nSmart != EXPECT_TOTAL_ROWS) throw new IllegalStateException("分区未覆盖全部类型");

        // ---- 写文件 ----
        writeCsv(dir.resolve("coverage-baseline.csv"),
                HEADER_11 + ",coverage_scope,extension_reason,frozen_status", frozen);
        writeCsv(dir.resolve("extension-smartcardio.csv"),
                HEADER_11 + ",extension_class,extension_reason", smartcardio);
        writeCsv(dir.resolve("extension-preview.csv"),
                HEADER_11 + ",extension_class,extension_reason", preview);
        writeCsv(dir.resolve("extension-protected-nested.csv"),
                "type_binary,extension_reason", prot);

        // ---- 冻结清单说明（含 SHA-256）----
        String[] artifacts = {
                "coverage-baseline.csv", "extension-smartcardio.csv",
                "extension-preview.csv", "extension-protected-nested.csv"
        };
        StringBuilder md = new StringBuilder();
        md.append("# Java SE 21 基准清单 · 正式冻结说明（阶段一）\n\n");
        md.append("> 冻结日期：").append(FREEZE_DATE).append(" ｜ 生成工具：`tools/FreezeBaseline.java`（JDK 21，零依赖）\n")
          .append("> 冻结依据：用户裁决「正式采用 3874 公开类型 · 177 包 · 20 内容模块」\n")
          .append("> 本文件与下列清单由工具生成，**不修改** `api-baseline.csv` 及任何既有审计证据。\n\n");
        md.append("## 1. 用户裁决的正式统计边界（冻结，不得再变更）\n\n");
        md.append("| 项 | 裁决 | 落实 |\n|---|---|---|\n")
          .append("| 正式清单边界 | **3874 公开类型 · 177 包 · 20 内容模块** | `coverage-baseline.csv` 的 3874 行（spec_status=SE） |\n")
          .append("| java.smartcardio | 排除正式分母，保留扩展清单 | `extension-smartcardio.csv`（13） |\n")
          .append("| protected 嵌套类型 | 排除正式分母，保留扩展清单 | `extension-protected-nested.csv`（168） |\n")
          .append("| Deprecated 类型 | 继续纳入 | 68（含 forRemoval 36）全部位于 `coverage-baseline.csv` |\n")
          .append("| Preview 类型 | 继续排除正式分母，单独登记 | 38 行标 `EXCLUDED_PREVIEW`，另存 `extension-preview.csv` |\n\n");
        md.append("## 2. 两层口径（规范原因，非统计矛盾）\n\n");
        md.append("- **清单登记边界（用户冻结）**：3874 类型 / 177 包 —— 即 JSR 396 §7 严格口径的公开类型全集，\n")
          .append("  其中 38 个类型级 Preview 类型仍在清单内（以 `coverage_scope=EXCLUDED_PREVIEW` 单列）。\n")
          .append("- **覆盖率计算分母（任务书 §3.3 派生）**：**3836 类型 / 176 包**。\n")
          .append("  `3874 − 38 = 3836`；包级另扣除 `java.lang.foreign`（25 个类型全部为 Preview，整包无正式 API），\n")
          .append("  故 `177 − 1 = 176`。8 个「仅含成员级预览方法」的类型（preview_members=yes）类型本身非预览，\n")
          .append("  **保留在分母**。Deprecated 68 个按裁决保留在分母。\n")
          .append("- 三个覆盖率指标（任务书 §4）的分母统一取 **3836 类型 / 176 包 / 20 模块**；\n")
          .append("  若后续裁决改为「Preview 保留在分母」，只需将 `EXCLUDED_PREVIEW` 行并入分母，数字回到 3874 / 177。\n\n");
        md.append("## 3. 文件清单与完整性（SHA-256）\n\n");
        md.append("> **校验前提**：上表 SHA-256 均基于 **LF 行尾内容**计算（本工具以 `\\n` 写出）。\n")
          .append("> Windows 默认 `core.autocrlf=true` 会把工作区检出为 CRLF，导致直接对工作区文件取哈希不匹配——这不是数据漂移。\n")
          .append("> 仓库根目录的 `.gitattributes` 已固定 `*.csv` / `*.md` / `*.java` / `*.sh` 为 `eol=lf`，重新检出后哈希可直接复现；\n")
          .append("> 若旧工作区仍为 CRLF，可执行 `git add --renormalize .` 后重新检出，或将内容按 CRLF→LF 归一化后再校验。\n\n");
        md.append("| 文件 | 行数（含表头） | 说明 | SHA-256 |\n|---|---|---|---|\n");
        for (String a : artifacts) {
            Path p = dir.resolve(a);
            long n = Files.readAllLines(p, StandardCharsets.UTF_8).size();
            md.append("| `").append(a).append("` | ").append(n).append(" | ")
              .append(artifactNote(a, nSe, nDenom, nSmart, nPreview, prot.size()))
              .append(" | `").append(sha256(p)).append("` |\n");
        }
        md.append("\n## 4. 阶段三使用约定\n\n")
          .append("阶段三在 `coverage-baseline.csv` 上**追加** `mapping_status`（Planned/Implemented/Verified/")
          .append("Excluded/Pending）及场景、代码位置、证据、未覆盖原因等列；\n")
          .append("`EXCLUDED_PREVIEW` 行的 mapping_status 固定为 `Excluded`（原因：任务书禁止 Preview API 作为正式实现）。\n")
          .append("三份扩展清单**不进入**覆盖率分母，仅作完整性登记与审计追溯。\n\n")
          .append("## 5. 复现\n\n```bash\n")
          .append("D:/Java/ms-21.0.13.0.2/bin/java tools/FreezeBaseline.java docs/phase1/data\n")
          .append("```\n工具对冻结常量（类型/包/扩展/Deprecated 计数）做硬断言，任何数量变化都会直接报错而非静默改写；\n")
          .append("上表 SHA-256 用于留痕与人工复核，工具本身不校验历史哈希。若需验证当前工作区是否与冻结内容一致，请按 §3 的 LF 前提比对。\n");
        Files.writeString(dir.resolve("freeze-manifest.md"), md, StandardCharsets.UTF_8);

        // ---- 摘要 ----
        System.out.println("=== 阶段一冻结完成 " + FREEZE_DATE + " ===");
        System.out.println("清单登记边界     : " + nSe + " 类型 / 177 包 / 20 模块");
        System.out.println("覆盖率分母       : " + nDenom + " 类型 / " + denomPkgs.size() + " 包 / 20 模块");
        System.out.println("  EXCLUDED_PREVIEW: " + nPreview + "（含 java.lang.foreign 整包 " + 25 + "）");
        System.out.println("  Deprecated 保留 : " + nDeprecated + "（forRemoval " + nForRemoval + "）");
        System.out.println("扩展清单         : smartcardio " + nSmart + " / protected " + prot.size() + " / preview " + nPreview);
        System.out.println("输出: coverage-baseline.csv, extension-*.csv, freeze-manifest.md");
    }

    private static String[] withScope(String[] c, String scope, String reason) {
        String[] r = new String[14];
        System.arraycopy(c, 0, r, 0, 11);
        r[11] = scope;
        r[12] = reason.isEmpty() ? "" : q(reason);
        r[13] = "FROZEN";
        return r;
    }

    private static String[] withExtra(String[] c, String cls, String reason) {
        String[] r = new String[13];
        System.arraycopy(c, 0, r, 0, 11);
        r[11] = cls;
        r[12] = q(reason);
        return r;
    }

    private static String artifactNote(String a, int se, int denom, int smart, int prev, int prot) {
        return switch (a) {
            case "coverage-baseline.csv" -> "SE 正式清单 " + se + " 行（分母 " + denom + " + Preview " + prev + "）";
            case "extension-smartcardio.csv" -> smart + " 行，排除分母";
            case "extension-preview.csv" -> prev + " 行，排除分母";
            case "extension-protected-nested.csv" -> prot + " 行，排除分母";
            default -> "";
        };
    }

    private static void writeCsv(Path p, String header, List<String[]> rows) throws Exception {
        StringBuilder sb = new StringBuilder(header).append('\n');
        for (String[] r : rows) sb.append(String.join(",", r)).append('\n');
        Files.writeString(p, sb, StandardCharsets.UTF_8);
    }

    private static String q(String s) {
        if (s.contains(",") || s.contains("\"")) return '"' + s.replace("\"", "\"\"") + '"';
        return s;
    }

    private static void check(String label, int actual, int expect) {
        if (actual != expect) {
            throw new IllegalStateException(label + " = " + actual + "，冻结预期 " + expect + "；拒绝生成冻结清单");
        }
    }

    private static String sha256(Path p) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p));
        StringBuilder s = new StringBuilder();
        for (byte b : d) s.append(String.format("%02x", b));
        return s.toString();
    }
}
