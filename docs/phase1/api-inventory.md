# Java SE 21 标准 API 基准清单与盘点报告（阶段一 · 正式冻结版）

> 状态：**已冻结（2026-11-20）**——用户裁决的正式统计边界见 §0；基准数据与全部审计证据自此只读。
> 盘点日期：2026-11-20 ｜ 修订日期：2026-11-20（v2，用户六项修订指令）｜ 冻结日期：2026-11-20
> 盘点工具：`tools/ApiInventoryScanner.java`；审计工具：`tools/ApiSpecDiff.java`、`tools/DocTypeClosure.java`、`tools/verify-page.sh`；冻结工具：`tools/FreezeBaseline.java`（均可复现）
> 运行环境：Microsoft Build of OpenJDK **21.0.13.0.2+1-LTS**（2026-10-22，win-x64）
>
> **版本沿革**：v1 初版 → v2 修订版（六项指令：双口径与 smartcardio 表述、JEP 442 第三次预览、record 历史说明、逐类型全量差异审计、差异逐项处理、Preview/Deprecated 核验并留证）→ **本版：正式冻结**（落实用户五项统计边界裁决，新增 §0 与冻结数据交付物）。基准数据文件全程未因差异被修改，理由见 §6.5。

---

## 0. 正式冻结边界（用户裁决，2026-11-20）

用户裁决：**正式采用 3874 公开类型 · 177 个包 · 20 个内容模块**，并给出四项边界处理。本报告据此冻结。

### 0.1 裁决落实表

| # | 裁决项 | 冻结结论 | 数据落实 |
|---|---|---|---|
| 1 | 正式统计边界 | **3874 类型 / 177 包 / 20 内容模块**（JSR 396 §7 严格口径） | `data/coverage-baseline.csv`（3874 行，`spec_status=SE`） |
| 2 | `java.smartcardio` | 排除正式分母，**保留扩展清单** | `data/extension-smartcardio.csv`（13 行 / 1 包） |
| 3 | protected 嵌套类型 | 排除正式分母，**保留扩展清单** | `data/extension-protected-nested.csv`（168 行） |
| 4 | Deprecated 类型 | **继续纳入**分母 | 68 个（含 `forRemoval=true` 36）全部在冻结清单内 |
| 5 | Preview 类型 | **继续排除正式分母，单独登记** | 38 行标 `coverage_scope=EXCLUDED_PREVIEW`，另存 `data/extension-preview.csv` |

### 0.2 两层口径（清单边界 ≠ 覆盖率分母，含规范原因）

冻结复核数据时发现一处必须明确的口径层次。**不修改任何统计数字，仅显式区分**：

| 层次 | 类型 | 包 | 模块 | 依据 |
|---|---|---|---|---|
| **A. 清单登记边界（用户冻结）** | **3874** | **177** | 20 | JSR 396 §7 严格口径的公开类型全集；38 个 Preview 类型仍在清单内单列 |
| **B. 覆盖率计算分母（任务书 §3.3 派生）** | **3836** | **176** | 20 | A 扣除 38 个类型级 Preview 与 1 个纯 Preview 包 |

差异的规范原因（逐项，可复算）：

- `3874 − 38 = 3836`：任务书 §3.3 规定「Java SE 21 Preview API … 不得混入正式覆盖率分母」。38 个类型级 Preview 全在 `java.base`，在冻结清单中以 `EXCLUDED_PREVIEW` 单列登记（阶段三 mapping_status 固定为 `Excluded`）。
- `177 − 1 = 176`：`java.lang.foreign` 的 25 个类型**全部**为 Preview（JEP 442），整包无任何正式 API；按同一条款，包级分母不应包含该包。
- 8 个「仅含成员级预览方法」的类型（`preview_members=yes`，如 `java.lang.Class`、`FileChannel`）**类型本身不是预览 API，保留在分母**；68 个 Deprecated 类型按裁决 4 保留在分母。
- 三个覆盖率指标（包级 / 类型级 / 完整包）的分母统一取 **B**；若后续裁决改为「Preview 保留在分母」，只需将 `EXCLUDED_PREVIEW` 行并入，数字回到 A（3874 / 177）。

> 本节是口径透明性说明，**不改变用户冻结的 3874 / 177 / 20 边界**；覆盖率审计实际使用的分母为 3836 / 176 / 20。若用户不认可该派生，可显式要求改回 A 口径（一行配置即可）。

### 0.3 冻结交付物（新增文件，既有数据零改动）

由 `tools/FreezeBaseline.java`（JDK 21、零第三方依赖、内含全部冻结常量的硬断言）从**只读**的 `api-baseline.csv` 与 `verification/closure-missing.csv` 派生；SHA-256 全量留痕见 `data/freeze-manifest.md`：

| 文件 | 内容 |
|---|---|
| `data/coverage-baseline.csv` | 正式冻结清单 3874 行（14 列 = 原 11 列 + `coverage_scope` / `extension_reason` / `frozen_status`）；阶段三在其上扩 `mapping_status` |
| `data/extension-smartcardio.csv` | 13 行（排除分母，完整性登记） |
| `data/extension-preview.csv` | 38 行（排除分母，单独登记） |
| `data/extension-protected-nested.csv` | 168 行（排除分母，完整性登记） |
| `data/freeze-manifest.md` | 冻结边界、口径层次、SHA-256、阶段三使用约定 |

**声明**：`api-baseline.csv`、`packages.csv`、`packages.md` 及 `verification/` 下全部证据文件**未被修改**；冻结只新增派生文件。

---

## 1. 目标与范围

依据官方 Java SE 21 规范建立「模块 → 包 → 公开类型」三级基准清单，作为后续 API 覆盖审计的**统计分母**。本阶段只盘点、不编码游戏功能。

**统计口径（双口径并行）**：

| 口径 | 定义 | 模块 | 包 | 类型 |
|---|---|---|---|---|
| **主口径（JSR 396 严格）** | JSR 396 §7 定义的 Java SE 标准模块集合（= 从 `java.se` 聚合模块可达的标准模块，20 个内容模块） | 20 | **177** | **3874** |
| **宽口径（补充观察用）** | 主口径 + `java.smartcardio`（官方 API 文档按 `java.*` 前缀将其归入 "Java SE" 分组，见 §5） | 21 | 178 | 3887 |

清单登记边界采用**主口径**（用户冻结，见 §0.1）；覆盖率计算分母按任务书 §3.3 从主口径扣除 Preview 后为 **3836 类型 / 176 包**（见 §0.2）。宽口径数据保留在同一 CSV 中，可按 `spec_status` 过滤。

纳入统计的公开类型：`public class` / `abstract class` / `interface` / `enum` / `@interface`（annotation）/ `sealed class` / `sealed interface`，以及**独立计数的嵌套公开类型**（须整条外围类链 public，见 §3.1-4）。关于 record 见 §4.5 的统计结论。

排除项（不计入分母，单独记录）：
- `jdk.*` JDK 专有模块（如 `jdk.httpserver`、`jdk.jfr`、`jdk.incubator.vector` 等）；
- 非导出包与限定导出包中的类型（`jdk.internal.*`、`sun.*` 等）；
- Java SE 21 **类型级 Preview API**（38 个，单独成列，见 §4.4 与 §7）；
- Incubator 模块（`jdk.incubator.*`，本就不属规范模块）；
- javadoc 文档化的 **protected 嵌套类型**（168 个，见 §6.3——文档收录但非 public 类型，完整清单保留）。

保留项：所有 `@Deprecated` 与 `@Deprecated(forRemoval=true)` 类型仍在分母中（68 / 36 个，单独标记，核验见 §8）。

## 2. 权威数据来源

| 来源 | 用途 | 核验结果 |
|---|---|---|
| Java SE 21 API Specification（https://docs.oracle.com/en/java/javase/21/docs/api/） | 规范公开 API 基准 | 全量差异审计通过（§6） |
| ├ `index.html` 模块索引 | 模块清单 | 通过（§9-1） |
| ├ `allclasses-index.html` / `allpackages-index.html` | 全类型/全包索引（含已知盲区，见 §6.4） | 通过（§6） |
| ├ `preview-list.html`（Preview API 官方清单页） | Preview 类型核验 | 38/38 精确一致（§7） |
| └ `deprecated-list.html` | Deprecated 类型核验 | 68 全命中（§8） |
| JSR 396（Java SE 21 平台规范，https://cr.openjdk.org/~iris/se/21/spec/latest/） | 模块集权威定义、预览/移除/待移除 API 清单 | 通过（§6、§7、§8） |
| JEP 442（openjdk.org/jeps/442，标题 "Foreign Function & Memory API (Third Preview)"） | FFM 预览次数 | 引用（§4.4） |
| JEP 395（openjdk.org/jeps/395，"Records"） | record 语言特性定稿版本 | 引用（§4.5） |
| 本机 JDK 21.0.13 运行时镜像（`jrt:/`）+ 模块描述符 + 反射 | 类型枚举与标记 | 基准数据本体 |

原始证据文件（HTML/JS 原样保存）：`docs/phase1/data/verification/`（见 §10 清单）。

`javax.*` 包（如 `javax.swing`、`javax.sql`、`javax.naming`）位于 `java.*` 规范模块内部，属于 Java SE 21 正式 API（非 Jakarta EE）。历史上与 Java EE 重叠、且已被 SE 9–11 移除的模块在 SE 21 中不存在，无需排除。

## 3. 盘点方法（可复现）

1. **模块集**：以 JSR 396 §7 的定义为准——SE 模块 = 从 `java.se` 聚合模块可达的标准模块。工具对 `java.se` 的 `requires transitive` 闭包做 BFS，得到 **20 个内容模块**（聚合模块自身不导出包，不计入）。另将 `java.smartcardio` 单独纳入宽口径并标记 `spec_status=JAVA_PREFIX_ONLY`。
2. **包集**：仅取各模块描述符中**非限定导出**（`exports` 无 `to` 目标）的包。限定导出视为非规范公开 API。
3. **类型集**：遍历 `jrt:/modules/<模块>` 下全部 `.class` 文件；对导出包中的每个类用 `Class.forName(name, false, platformLoader)` 反射加载（**不初始化**），仅保留 `public` 修饰的类型；排除 `module-info`、`package-info`、匿名/局部/编号合成类。
4. **嵌套可达性规则**：嵌套公开类型必须**整条外围类链全部 public** 才计入（否则为不可达 API，javadoc 不为其生成文档，如 `java.lang.WeakPairMap.Pair` 系列）。
5. **废弃标记**：反射读取 `@Deprecated` / `@Deprecated(forRemoval=true)`（RUNTIME 保留，反射可见，方法可靠）。
6. **预览标记**：`@PreviewFeature`（`jdk.internal.javac.PreviewFeature`）为 CLASS 保留（`RuntimeInvisibleAnnotations`），运行时反射不可见；工具内置最小 class 文件解析器，精确读取**类级**注解属性；成员级预览引用另行标记为 `preview_members`（javap 已实证注解位置）。

### 3.1 过程中发现并修正的技术问题（均有证据）

| # | 现象 | 根因 | 修正 |
|---|---|---|---|
| 1 | 9 个模块 378 个类型加载失败 | `java.compiler`、`java.sql` 等模块的类定义在 **platform 类加载器** | 改用 `ClassLoader.getPlatformClassLoader()` |
| 2 | 预览类型数为 0 | `@PreviewFeature` 是 CLASS 保留，反射不可见 | javap 确证后改为解析 class 文件 |
| 3 | 46 个「预览」类型中混入 `java.lang.Class` 等 | 字节扫描只能证明「引用过」该注解（成员级） | 精确解析类级注解属性 → 38 个类型级预览 |
| 4 | `java.lang` 数出 132，官方文档 129 | 扫入包私有外类中的 public 嵌套类 | 增加外围类链 public 校验（全网剔除 28 个不可达嵌套类型） |

## 4. 基准统计

### 4.1 总量（主口径 3874 / 宽口径 3887）

| 口径 | 模块 | 包 | 公开类型 | 顶层 | 嵌套 |
|---|---|---|---|---|---|
| **主口径（JSR 396 严格）** | 20 | **177** | **3874** | 3362 | 512 |
| 宽口径（+ java.smartcardio） | 21 | 178 | 3887 | 3362 | 525 |

（嵌套 525 − 512 = 13，恰为 smartcardio 的嵌套类型数。）

### 4.2 模块级统计（主口径；斜体行为宽口径独有）

| 模块 | 包 | 类型 | 模块 | 包 | 类型 |
|---|---|---|---|---|---|
| java.base | 54 | 1391 | java.prefs | 1 | 9 |
| java.compiler | 6 | 136 | java.rmi | 5 | 55 |
| java.datatransfer | 1 | 12 | java.scripting | 1 | 12 |
| java.desktop | 50 | 1524 | java.security.jgss | 2 | 16 |
| java.instrument | 1 | 6 | java.security.sasl | 1 | 10 |
| java.logging | 1 | 17 | *java.smartcardio* | *1* | *13* |
| java.management | 9 | 195 | java.sql | 2 | 74 |
| java.management.rmi | 1 | 9 | java.sql.rowset | 3 | 30 |
| java.naming | 6 | 102 | java.transaction.xa | 1 | 3 |
| java.net.http | 1 | 22 | java.xml | 25 | 193 |
| | | | java.xml.crypto | 6 | 58 |

**规模结构**：`java.base`（1391）与 `java.desktop`（1524）合计 75.1% 的类型，是覆盖率的决定性战场。

### 4.3 类型种类分布

| 种类 | 数量 | 种类 | 数量 |
|---|---|---|---|
| class | 2256 | annotation | 24 |
| interface | 987 | sealed-class | 30 |
| abstract-class | 446 | sealed-interface | 35 |
| enum | 109 | **record** | **0** |

### 4.4 废弃与预览

- **@Deprecated：68 个**（其中 `forRemoval=true`：36 个），全部保留在分母。核验见 §8。
- **类型级 Preview API：38 个**（全部在 java.base，单列不计入分母），核验见 §7：
  - `java.lang.foreign.*`（25 个）—— **JEP 442 外部函数与内存 API（第三次预览）**（JEP 442 官方标题即 "Foreign Function & Memory API (Third Preview)"，已引用核验）
  - `java.util.concurrent.StructuredTaskScope` 系列（6 个）—— JEP 453 结构化并发（预览）
  - `java.lang.ScopedValue` 系列（2 个）—— JEP 446 作用域值（预览）
  - `java.lang.StringTemplate` 系列（3 个）+ `java.lang.runtime.TemplateRuntime`（1 个）—— JEP 430 字符串模板（预览）
  - `java.util.FormatProcessor`（1 个）
- 另有 **8 个类型仅含成员级预览方法**（`java.lang.Class`、`Module`、`ModuleLayer.Controller`、`MethodHandles`、`StringConcatFactory`、`FileChannel`、`javax.lang.model.element.TypeElement/VariableElement`），标记为 `preview_members`，类型本身**不是**预览 API，计入分母。

### 4.5 关于 record 的统计结论

- **语言特性层面**：record 是 Java 语言特性，**已于 Java 16 经 JEP 395 正式定稿**（引用：openjdk.org/jeps/395 "Records"）。
- **API 类型集合层面（本报告的统计口径）**：对 Java SE 21 规范公开 API 的类型集合（3874/3887 个）逐类型以 `Class.isRecord()` 全量扫描，**record 类型数量为 0**；官方 `allclasses-index.html` 全文档（含 `jdk.*` 模块）中亦不存在任何 "record class in …" 条目，两侧证据一致。
- 该 0 值**仅是 Java SE 21 该版本 API 类型集合的统计结论**，不构成对 record 语言特性成熟度或其后续版本 API 中出现情况的任何表述（例如后续 JDK 版本的正式 API 中包含 record 类型属另一版本的另一统计事实，不在本报告口径内）。

## 5. `java.smartcardio` 的规范归属（事实与表述规范）

**事实链（三层证据）**：
1. **JSR 396 §7**：Java SE 21 规范将标准包组织为 **21 个标准模块**（含 `java.se` 聚合器），完整清单中**没有** `java.smartcardio`；并定义「一个模块属于 Java SE Platform Specification，当且仅当它是从 `java.se` 可达的标准模块」。
2. **JDK 21.0.13 运行时**：`--describe-module java.se` 的 requires 清单同样不含 `java.smartcardio`（其模块名为 `java.` 前缀，属于 JDK 提供的标准命名模块，但不在 `java.se` 聚合图中）。
3. **官方 API 文档站**：`index.html` 的 "Java SE" 分组按**模块名 `java.` 前缀**划分，因此将 `java.smartcardio`（及其 `javax.smartcardio` 包，13 个类型）归入该分组展示。

**本项目表述规范**：`java.smartcardio` 描述为——**「官方 Java SE API 文档分组收录（按 `java.*` 前缀），但不属于 JSR 396 §7 定义的标准模块集合」**的标准命名模块。它不是 JDK 专有 API（`jdk.*` 才是 JDK 专有模块），也不属于 Java SE Platform Specification 的模块集合。

**处理**：基准数据纳入并标记 `spec_status=JAVA_PREFIX_ONLY`（1 包 13 类型）；正式清单边界用主口径；宽口径可按该字段过滤得到。该口径已由用户于 2026-11-20 冻结裁决（§0.1-2、§12-1）：smartcardio 排除正式分母，保留 13 行扩展清单。

## 6. 逐类型全量差异审计（官方 API 文档 vs 扫描器）

### 6.1 审计方法（三方证据 + 逐页验证）

| 证据源 | 性质 | 用途 |
|---|---|---|
| `allclasses-index.html`（官方，1.9MB） | javadoc 生成的全类型索引 | 独立比对顶层与静态嵌套类型 |
| `type-search-index.js`（官方搜索索引） | 独立第二来源 | 交叉验证索引盲区 |
| `DocTypeClosure`（`java.se`+smartcardio 运行时闭包，按 javadoc 文档化规则） | 推导侧 | 枚举「文档化类型全集」的嵌套部分 |
| **284 次官方文档页抓取**（HTTP 状态 + 页面标题比对） | 逐类型直接证据 | 对全部差异候选逐一留证 |

javadoc 文档化规则（默认 protected 模式，已实证）：**顶层类型须 public；嵌套类型在其直接外围类型已文档化时，public 或 protected 均生成文档页**。

### 6.2 对账关系（数字完全闭环）

```
扫描器基准（public 口径）      3887 = 3362 顶层 + 525 嵌套(409 静态 + 116 内部类)
官方全类索引（allclasses）     3780 = 3362 顶层 + 418 静态嵌套(409 public + 9 protected)
文档化闭包（javadoc 规则）     4055 = 3888 public* + 167 protected        *含 1 个链上 protected 的 public
对账：3780 = 3887 − 116(索引盲区：非静态内部类) + 9(索引收录的 protected 静态嵌套)
```

### 6.3 差异清单与逐项处理

| 差异类别 | 数量 | 逐项处理 | 证据 |
|---|---|---|---|
| **缺失**（文档化、扫描器未收录） | **168** | 构成：**167 个 protected 嵌套类型**（AWT/Swing `Accessible*` 系列、`*BufferStrategy`、L&F 内部 handler 等，多为 JavaBeans/无障碍辅助类）+ **1 个外围链含 protected 的 public 嵌套**（`javax.swing.JEditorPane.JEditorPaneAccessibleHypertextSupport.HTMLLink`）。规范原因：javadoc 默认文档化 public+protected 成员，而任务书口径为「public 公开类型」→ **维持排除，完整清单保留**（`closure-missing.csv` / `extension-protected-nested.csv`），该口径已由用户冻结裁决（§0.1-3、§12-2） | 168/168 页面 HTTP 200 + 标题逐一匹配（`page-verify-missing.csv`） |
| **额外**（扫描器收录、文档未收录） | **0**（初始比对曾显示 116，全部排除） | 初始比对中 116 个「额外」类型全部为**非静态内部类**（如 `AbstractQueuedSynchronizer.ConditionObject`、`GroupLayout.Group`）。规范原因：**javadoc 的全类索引页与 type-search-index.js 均不收录非静态内部类**（已用 `ConditionObject`：索引 0 处、独立文档页存在 200/标题匹配 实证），**其文档页真实存在**，故不构成「额外」——这 116 个类型属扫描器正确收录 | 116/116 页面 HTTP 200 + 标题逐一匹配（`page-verify-extras.csv`） |
| **无法判断** | **0** | 解析歧义条目 0：初始解析中出现的 `java.lang.class`、`java.util.deque` 等假条目均定位为索引页**描述列 `{@link}` 锚点**与搜索标签，修正解析（仅取 `col-first` 列 + 剥离 `<wbr>`）后归零 | `diff-indeterminate.csv`（空） |
| 包级差异 | 0 | 官方 `allpackages-index` 与扫描器包集（178，宽口径）完全一致 | `diff-packages.csv`（空） |

### 6.4 javadoc 索引盲区（本审计的方法学发现）

- `allclasses-index.html` 与 `type-search-index.js` **均不收录非静态内部类**（其文档页存在）；两者对静态嵌套收录 public 与 protected。使用官方索引页做全量比对时必须以文档页存在性交叉验证，否则将产生 116 个假「额外」。
- 索引页/废弃页的**描述列含 `{@link}` 锚点与搜索标签**（如 `loggers`、`class`、`memory segment`），直接抓取 `<a>` 会产生假条目；本审计仅取 `col-first`（名称）列。

### 6.5 基准清单处理结论（不修改统计的声明）

全量审计的结论是：**扫描器 public 口径与「官方文档化类型全集的 public 子集」完全一致（缺失 0、额外 0、无法判断 0）**；168 个缺失项全部属于「文档化 protected 嵌套」这一**口径差异**，已给出规范原因并保留完整清单，**未为消除差异而修改任何统计结果或数据文件**。是否将 168 个文档化 protected 嵌套类型纳入分母，属于口径决策；用户已于 2026-11-20 冻结裁决「维持排除、保留扩展清单」（§0.1-3、§12-2）。

## 7. Preview API 清单核验（38/38 精确一致）

**证据链**：
1. JSR 396 §6（`jsr396-spec.html` 原文摘录）：*"The list of Java APIs which are preview features may be found in the **Preview API section of the API Specification**."*——规范本身将预览 API 清单**指向** API 文档的 Preview 专页。
2. 官方 `preview-list.html`（Preview API 官方清单页，已下载存档）：提取类型级条目（排除描述列 `memory segment(s)` 搜索标签假条目）= **38 个，与扫描器 38 个逐一 diff 为零差异**（证据：`preview-docs-types.txt`）。
3. 附加一致性：38 个的包分布（`java.lang.foreign` 25、`java.util.concurrent` 6、`java.lang` 5、`java.lang.runtime` 1、`java.util` 1）与 JSR 396 §5 所列预览特性（FFM/结构化并发/作用域值/字符串模板）一一对应；JEP 442/453/446/430 归属见 §4.4。

## 8. Deprecated 清单核验（68 全命中）

**证据链**：
1. 官方 `deprecated-list.html`（已下载存档）：java.* 范围类型级条目去重 = 74 个。
2. 与扫描器 68 个比对：**68 个全部出现在官方清单中（零遗漏）**；文档侧多出 6 个，逐项处理：
   - 4 个为 **protected 嵌套的废弃类型**（`JComponent.AccessibleJComponent.AccessibleFocusHandler`、`BasicMenuItemUI.MouseInputHandler`、`MetalFileChooserUI.FileRenderer`、`MetalFileChooserUI.SingleClickListener`）——与 §6.3 缺失 168 同口径原因（javadoc 文档化 protected），维持排除，与 JSR 396 §9 的待移除清单内容（其中列有 `BasicMenuItemUI.MouseInputHandler` 等）相互印证；
   - 2 个为**描述列链接假阳性**（`java.net.SocketImpl`、`DatagramSocketImpl`——出现在废弃的 `Socket`/`DatagramSocket` 构造器条目描述文本 "constructor that takes an implementation" 中；javap 确认两类型自身无 `RuntimeVisibleAnnotations`，扫描器 `deprecated=no` 正确）。
3. `forRemoval=true` 36 个：与 JSR 396 §9 "APIs proposed for removal" 的类型级清单逐一比对一致（样例：`java.applet.Applet`、`java.lang.SecurityManager`、`javax.swing.plaf.basic.BasicScrollPaneUI.HSBChangeListener`（public，在基准中）、`javax.security.cert.*` 等）。
证据文件：`deprecated-docs-types.txt`（74 行）、`jsr396-spec.html`（§8/§9 原文）。

## 9. 交叉核验总表（冻结版）

| # | 核验点 | 官方数据 | 本工具 | 结果 |
|---|---|---|---|---|
| 1 | SE 模块集（JSR 396 §7） | 21 标准模块（含聚合器），20 内容模块 | 20 | ✅ 一致 |
| 2 | java.base 导出包（官方 module-summary） | 54 | 54 | ✅ 一致 |
| 3 | 包集全量（`allpackages-index`，178 宽口径） | 178 | 178 | ✅ 一致 |
| 4 | 顶层类型全量（`allclasses-index`） | 3362 | 3362 | ✅ 一致 |
| 5 | 静态嵌套 public（`allclasses-index`） | 409 | 409 | ✅ 一致 |
| 6 | 非静态内部类（文档页逐页验证） | 116（页页存在） | 116 | ✅ 一致 |
| 7 | 类型全量（主口径） | 文档化闭包 − protected 口径差 | 3874 | ✅ 见 §6 |
| 8 | Preview 清单（`preview-list.html` + JSR 396 §6） | 38 | 38 | ✅ 精确一致 |
| 9 | Deprecated 清单（`deprecated-list.html`） | 68+4+2假阳性 | 68 | ✅ 全命中 |
| 10 | forRemoval（JSR 396 §9） | 36 | 36 | ✅ 一致 |
| 11 | SE 21 已移除 API（JSR 396 §8） | `java.lang.Compiler` 等不存在 | 基准中不存在 | ✅ 一致 |
| 12 | java.net.http 类型数（package-summary 抽样） | 22 | 22 | ✅ 一致 |
| 13 | java.lang 类型数（package-summary 抽样） | 129 | 129 | ✅ 一致 |

## 10. 数据文件与 Schema

基准数据（`docs/phase1/data/`）：

| 文件 | 内容 | 行数 |
|---|---|---|
| `api-baseline.csv` | 全部纳入统计的公开类型（v2 未改动：全量审计未发现需修改项；冻结后只读） | 3888（含表头） |
| `packages.csv` | 包级统计 | 179 |
| `packages.md` | 包级统计 Markdown 渲染 | 182 |

**正式冻结交付物**（`docs/phase1/data/`，由 `tools/FreezeBaseline.java` 生成，SHA-256 见 `freeze-manifest.md`）：

| 文件 | 内容 | 行数 |
|---|---|---|
| `coverage-baseline.csv` | 正式冻结清单（SE 3874 行；`coverage_scope` = 3836 `IN_DENOMINATOR` + 38 `EXCLUDED_PREVIEW`） | 3875（含表头） |
| `extension-smartcardio.csv` | smartcardio 扩展清单（排除分母） | 14 |
| `extension-preview.csv` | 类型级 Preview 扩展清单（排除分母，单独登记） | 39 |
| `extension-protected-nested.csv` | 文档化 protected 嵌套扩展清单（排除分母） | 169 |
| `freeze-manifest.md` | 冻结边界、两层口径、SHA-256、阶段三使用约定 | 48 |

`coverage-baseline.csv` 列（14 列，UTF-8）：
`module, package, type_binary, type_display, kind, nesting, deprecated, for_removal, preview, preview_members, spec_status, coverage_scope, extension_reason, frozen_status`

审计证据（`docs/phase1/data/verification/`，均随源码交付）：

| 文件 | 内容 |
|---|---|
| `allclasses-index.html`、`allpackages-index.html`、`preview-list.html`、`deprecated-list.html`、`type-search-index.js`、`jsr396-spec.html` | 官方原始证据（下载存档） |
| `diff-missing.csv` / `diff-extra.csv` / `diff-kind-mismatch.csv` / `diff-indeterminate.csv` / `diff-packages.csv` / `diff-summary.txt` | 全量差异比对输出（索引侧） |
| `doc-closure.csv` / `closure-missing.csv` / `closure-extra.csv` | 文档化闭包（4055）与 168 缺失清单 |
| `fetch-missing.txt` / `fetch-extras.txt` / `page-verify-missing.csv` / `page-verify-extras.csv` | 284 个差异候选的官方文档页逐页验证（HTTP 状态 + 标题匹配，全部 yes） |
| `preview-docs-types.txt` / `deprecated-docs-types.txt` | Preview 38 清单、Deprecated 74 清单（官方侧提取） |

`api-baseline.csv` 列（11 列，UTF-8）：
`module, package, type_binary, type_display, kind, nesting(top|nested), deprecated, for_removal, preview, preview_members, spec_status(SE|JAVA_PREFIX_ONLY)`

## 11. 复现步骤（完整链条）

```bash
# 前置：JDK 21（本项目固定 21.0.13）；Git Bash / POSIX shell；可访问 docs.oracle.com 与 cr.openjdk.org
JDK=D:/Java/ms-21.0.13.0.2/bin/java
V=docs/phase1/data/verification

# 1) 生成基准清单（3887 类型 / 178 包 / 预览 38 / 加载失败 0）
$JDK tools/ApiInventoryScanner.java --out docs/phase1/data

# 2) 下载官方证据（一次性，存入 verification/）
curl -sL -A "Mozilla/5.0" -o $V/allclasses-index.html  https://docs.oracle.com/en/java/javase/21/docs/api/allclasses-index.html
curl -sL -A "Mozilla/5.0" -o $V/allpackages-index.html https://docs.oracle.com/en/java/javase/21/docs/api/allpackages-index.html
curl -sL -A "Mozilla/5.0" -o $V/preview-list.html      https://docs.oracle.com/en/java/javase/21/docs/api/preview-list.html
curl -sL -A "Mozilla/5.0" -o $V/deprecated-list.html   https://docs.oracle.com/en/java/javase/21/docs/api/deprecated-list.html
curl -sL -A "Mozilla/5.0" -o $V/jsr396-spec.html       https://cr.openjdk.org/~iris/se/21/spec/latest/

# 3) 全量差异审计（索引侧：缺失 9/额外 116/无法判断 0 —— 见 §6.4 盲区说明）
$JDK tools/ApiSpecDiff.java

# 4) 文档化闭包与 168 缺失清单（含 fetch-missing.txt URL 清单）
$JDK tools/DocTypeClosure.java

# 5) 逐页验证（284 页，并行；预期全 200 + 标题匹配 yes）
sed 's/|/ /' $V/fetch-missing.txt | xargs -P 6 -L1 bash tools/verify-page.sh > $V/page-verify-missing.raw
sed 's/|/ /' $V/fetch-extras.txt  | xargs -P 6 -L1 bash tools/verify-page.sh > $V/page-verify-extras.raw

# 6) 冻结：由只读基准派生正式清单与四类扩展清单（含硬断言 + SHA-256 manifest）
$JDK tools/FreezeBaseline.java docs/phase1/data

# 校验点：基准 3887（主口径 3874）；全类索引 3780 = 3887−116+9；闭包 4055；额外 0；无法判断 0
# 冻结校验点：coverage-baseline 3874 行（IN_DENOMINATOR 3836 / EXCLUDED_PREVIEW 38）；
#             分母 176 包；扩展清单 13 / 38 / 168；Deprecated 68（forRemoval 36）全部保留
```

工具均无第三方依赖；`ApiSpecDiff`/`DocTypeClosure` 输出确定性（排序）。

## 12. 冻结裁决结果与遗留问题

| # | 问题 | 裁决 / 处理 | 状态 |
|---|---|---|---|
| 1 | `java.smartcardio` 口径 | **主口径 3874 为正式清单边界；smartcardio 排除分母，保留 13 行扩展清单** | ✅ 已冻结裁决（§0.1） |
| 2 | 168 个文档化 protected 嵌套类型 | **维持排除**（任务书口径为 public 公开类型），保留 168 行扩展清单 | ✅ 已冻结裁决（§0.1） |
| 3 | Preview 类型 | **排除正式分母、单独登记**（38 行 `EXCLUDED_PREVIEW` + 扩展清单） | ✅ 已冻结裁决（§0.1） |
| 4 | Deprecated 类型 | **继续纳入分母**（68 / forRemoval 36） | ✅ 已冻结裁决（§0.1） |
| 5 | 清单边界 3874/177 与覆盖率分母 3836/176 的层次 | 显式区分并给出规范原因，**未修改任何统计数字** | ✅ 已闭合（§0.2） |
| 6 | 官方文档站 21 GA 快照 vs 本地 21.0.13 运行时：全量审计零差异表明不影响 21 系列 API 面 | 已在 §6.1 注明前提 | 已闭合 |
| 7 | `javax.print`、`java.awt.datatransfer` 等无头可用性（环境相关） | 阶段三小样实验后定状态 | 保留 |
| 8 | javadoc 索引盲区（§6.4）属官方文档工具行为，未来版本可能变化；复现以文档页存在性为准 | 已写入方法学 | 已闭合 |

**冻结后状态**：阶段一正式冻结完毕（清单与证据只读）→ **阶段二游戏整体设计已转正式版，等待用户确认** → 用户确认阶段二后方进入阶段三「API 与游戏功能逐类型映射」（在 `data/coverage-baseline.csv` 上扩 `mapping_status` 列）。
