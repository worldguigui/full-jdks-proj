# 阶段三：API 与游戏功能映射方法（mapping-strategy）

> 适用对象：`docs/phase3/data/api-mapping.csv`（3874 行）、`package-plan.csv`（177 行）、`module-plan.csv`（20 行）
> 生成工具：`tools/Phase3Mapper.java`；规则数据：`docs/phase3/rules/package-rules.tsv`、`type-overrides.tsv`
> 输入基准：`docs/phase1/data/coverage-baseline.csv`（**只读，未做任何修改**）

---

## 1. 为什么用"规则表 + 生成器"而不是手写 3874 行

3874 行逐条手写会产生三类问题：判据不一致（同一类 API 前后判定不同）、无法复现（改口径要全表重写）、无法校验（漏行/重行无人发现）。因此本阶段把**判定**和**展开**分开：

| 层 | 内容 | 位置 |
|---|---|---|
| 判定层 | 177 个包的默认判定 + 复核保留清单（按功能写「要哪几个类型」）+ 类型级覆盖 + 功能锚点 | `rules/package-rules.tsv`、`rules/type-keep.tsv`、`rules/type-overrides.tsv`、`rules/feature-anchors.tsv`（人工撰写） |
| 展开层 | 把判定展开到冻结清单的每一行类型，生成场景/位置/证据字段 | `tools/Phase3Mapper.java`（确定性生成） |
| 校验层 | 行数、类型集合、状态合法性、必备字段、原因码 | 生成器内硬断言 + `data/mapping-checks.txt` |

改一条判据只需改一行 TSV 并重跑，全表与统计同步更新；这是阶段四"每批回填状态"的前提。

第二轮复核之所以能一次调整 600 余行，靠的就是这套分层：把"这个功能需要哪些类型"写成 `type-keep.tsv` 的一行，而不是逐条改 CSV。

---

## 2. 状态定义与本阶段产出

| 状态 | 含义 | 当前数量 |
|---|---|---|
| `Planned` | 计划使用：已确定游戏系统、功能锚点、使用场景、预期代码位置、实施批次 | 1684 |
| `Pending` | **功能未设计或现行约束下不可用**：既不进覆盖分子，也不进排除 | 128 |
| `Implemented` | 已实现有效使用：代码存在且通过构建 | 阶段四回填 |
| `Verified` | 已验证有效使用：源码/classfile 扫描 + 运行证据齐备 | 阶段四/五回填 |
| `Excluded` | 经论证不使用：必须带原因码与原因说明 | 2024（分母内）+ 38（Preview） |

`Pending` 是第二轮复核引入的。**第一轮把「没有 Pending」当成优点，实质是把不确定的东西强行判死**：`java.sql` 三簇在零依赖下根本连不上（无驱动），判 `Planned` 是假承诺；判 `Excluded` 又抹掉了「换个存储方案就能用」的事实。`javax.tools` 同理——价值明确，但规则语言与安全边界还没设计。

`Pending` 不是垃圾桶：**必须写清「为什么待定」与「等什么决定」**，并在报告 §九 列出取回条件。

---

## 3. 判定顺序（确定性，无歧义）

对清单中每一行，按以下顺序取第一个命中的规则：

1. **Preview 优先**：`coverage_scope=EXCLUDED_PREVIEW` ⇒ `Excluded` + `PREVIEW`，不参与任何包/类型规则。
2. **类型级覆盖**：命中 `type-overrides.tsv` ⇒ 用该行的状态与字段（`Inherit` 仅覆盖模块/系统/批次/场景，状态沿用下一层）；**显式 `Excluded` 优先级最高**，用来表达"即使包整体保留，这个类型也不要"。
3. **复核保留清单**：命中 `type-keep.tsv` 所属包 ⇒
   - 类型在 `keep` 列表内 ⇒ 取该行声明的状态（`Planned` / `Pending`）；
   - 不在列表内 ⇒ `Excluded` + 该行声明的 `rest_code` / `rest_reason`；
   - `keep=*` 表示整包取该行状态；`keep=-` 表示整包排除。
4. **包级默认**：用 `package-rules.tsv` 的判定。
5. **功能锚点解析**：`type-overrides.feature` → `type-keep.feature` → `package:<包>` → `module:<模块>`；`Planned`/`Pending` 解析不到锚点即生成失败。

第 3 层是复核的核心：**「这个包已 Planned」不再自动等于「包内所有类型都 Planned」**。包级理由只能证明"这个方向要做"，证明不了"这 72 个类型都要用"。

### 3.1 论证强度（justification）

| 值 | 含义 | 当前 |
|---|---|---|
| `TYPE_AUDITED` | 逐类型审计，写出该类型的具体用途 | 120 |
| `PKG_FEATURE` | 只有包级功能锚点，包内类型作为同一功能的组成部分保留 | 228 |
| `MODULE_DEFAULT` | 只有模块级锚点，未单独论证包级用途 | 1336 |

阶段四的硬性要求：**每批开工前，该批涉及的包必须从 `MODULE_DEFAULT` 升到 `PKG_FEATURE` 以上**（在 `feature-anchors.tsv` 补 `package:` 行）。这条不是文档建议，是批次准入条件。

---

## 4. 原因码（Excluded 必填）

| 码 | 含义 | 判定标准 | 本阶段数量 |
|---|---|---|---|
| `BIZ` | 业务无关 | 纯 CLI 游戏不存在该场景（Swing/AWT GUI、打印、LDAP、RMI、TLS、证书、XML 签名、JAAS、XA…） | 1950 |
| `ENV` | 环境限制 | **当前运行时确实不可用，且有探针证据**（`env-probe.txt` / `runtime-capability-probe.md`：系统剪贴板 8、无脚本引擎 12） | 20 |
| `DEPRECATED` | 已被取代 | `forRemoval` 体系（SecurityManager/AccessController）或已有正式替代（SAX1、RMI 桩/骨架、Observable、`javax.security.cert`） | 54 |
| `PREVIEW` | 预览禁用 | `coverage_scope=EXCLUDED_PREVIEW`，任务书 §3.3/§7 禁止 | 38（分母外） |
| `ACCESS` | 非 public | protected 嵌套类型，包外不可引用（本清单中实际为 0，见 §9） | 0 |
| `SPEC` | 规范边界/特殊用途 | 预留；本阶段未使用 | 0 |

`BIZ` 与 `ENV` 的区别很重要：前者是**设计选择**（我们不写打印），后者是**环境事实**（无头环境没有系统剪贴板、JDK 没有脚本引擎）。第二轮的修正重点就在这里：

- 打印 135、LDAP 24、桌面集成 30、`java.awt.print` 10 —— 原标 `ENV`，实际是 `BIZ`（没有产品需求），已改回；
- `java.awt.datatransfer` 12 个原全标 `ENV`，其中 `DataFlavor`/`Transferable` 等 4 个是纯数据模型、无头环境可用，改为 `BIZ`；
- **不得把「没有需求」包装成「环境限制」**——前者是产品判断，后者需要证据。

---

## 5. 归属规则：fc 模块 / 游戏系统 / 批次

- **fc 模块**按"主使用点"归属，不按包分类归属。例如 `java.io.Console` 属 `fc-cli`，同包的 `ObjectOutputStream` 属 `fc-persist`。
- **游戏系统**取 16 系统名（§4.1–4.16）之一；三项工程能力不属于玩法系统，单独标注"（工程）"：`扩展与模组基础设施（工程）`、`构建与覆盖审计（工程）`、`启动与模块装配（工程）`。
- **同一类型多处使用只计一次**（任务书 §4.4），归属记在首次/主使用点。因此 `fc-fleet` / `fc-colony` / `fc-science` / `fc-diplomacy` 在统计中为 0：它们的 API 需求由 `fc-world` 的共享抽象（集合、时间、账本、事件）承载——这正是"统一世界规则"的代价与证据，不是遗漏。
- **批次**是"首次实现批次"，不是"只能在这个批次用"：M0 映射的 `java.util` 会在 M1–M6 持续使用。

---

## 6. 场景与预期代码位置

**场景文本**（`use_scenario`）= 包级场景句 + 分号 + 类型角色短语。

- 包级场景句描述**这个包在游戏里解决什么问题**（例：`java.time.chrono` → "自研「启程历」Chronology/ChronoLocalDate/Era，与 ISO 历法互转"）。
- 类型角色短语由类型名后缀与 `kind` 推导（`*Exception` → 异常处理；`*Listener` → 回调；`enum` → 状态常量；`sealed-*` → 模式匹配分派；等）。
- 若该类型有**类型级覆盖且带场景**，则**逐字使用**覆盖文本，不再拼接角色短语（避免出现"Console 作为封闭层次根"这类机械句式）。

**预期代码位置**（`expected_location`）= `<fc 模块>/src/main/java/<包级主使用类>`，例如
`fc-world/src/main/java/fc/world/time/ExpeditionChronology.java`。

这是**包级主使用点**，不是"该类型一定只在这里出现"。理由：一个类通常同时使用同包的十余个类型（如 `SaveContainer` 用 `JarOutputStream`/`JarEntry`/`Manifest`），逐类型编造不同文件名只会制造无法核对的假精度。阶段四回填时应写**实际**文件与行号。

---

## 7. 证据字段约定

| 状态 | `evidence` 取值 |
|---|---|
| `Planned` | `—（Planned，待 Mx 实现后回填源码/classfile 扫描证据）` |
| `Pending` | `—（Pending：功能未设计，未进入实现计划）`，待定原因写在 `use_scenario` |
| `Excluded` | `—（不适用）`，原因写在 `uncovered_reason_code` / `uncovered_reason` |
| `Implemented` | 阶段四填：`<文件>:<行> <使用形式（声明/创建/调用/实现/继承/注解/反射/SPI）>` |
| `Verified` | 阶段五填：`scan:<报告锚点>` + 运行证据（重放脚本、日志、测试用例名） |

`notes` 列承载三类提示：`@Deprecated`（含 `forRemoval`）、`preview_members=yes`（类型正式但含预览成员，只能使用正式成员）、protected 嵌套标注。

---

## 8. 复现与校验

```bash
# 生成（固定环境：E:/jdks/ms-21.0.11，Microsoft Build of OpenJDK 21.0.11）
java tools/Phase3Mapper.java

# 环境能力探测（Excluded/ENV 的证据：无头/打印/音频/ImageIO）
java -Djava.awt.headless=true tools/EnvProbe.java docs/phase3/data/env-probe.txt

# 运行时能力取证（脚本引擎 0 / JDBC 驱动 0 —— javax.script 与 java.sql 判定的硬证据）
java tools/CapProbe.java docs/phase3/data/verification/runtime-capability-probe.md
```

生成器的硬断言（任一失败即非零退出，不写半成品）：

1. 输出行数 = 冻结清单行数（3874）；
2. 输出类型集合与清单逐一相等（无遗漏、无重复）；
3. 状态 ∈ {Planned, Implemented, Verified, Excluded, Pending}；
4. `Excluded` ⇒ 原因码 ∈ 六类且原因说明非空；
5. `Planned` ⇒ 场景、预期位置、fc 模块、批次（`M0`–`M6`）、功能锚点、论证来源齐备；
6. `Pending` ⇒ 场景（待定原因）、fc 模块、论证来源齐备，且批次/位置为空；
7. Preview 行必须 `Excluded`/`PREVIEW`；
8. 包规则恰好覆盖 177 包；类型规则不得指向清单外类型；
9. `type-keep` 的包必须在清单内，保留类型必须属于该包，且不得与 `type-overrides` 冲突（保留 `Inherit` 语义时除外）；
10. 每个 `Planned`/`Pending` 行都必须解析到 `feature-anchors.tsv` 中的一个锚点。

---

## 9. 口径核对：protected 嵌套（168）是否在分母内

阶段一裁决"protected 嵌套类型排除分母，保留扩展清单"，但 `coverage-baseline.csv` 的 `coverage_scope` 只有 `IN_DENOMINATOR` / `EXCLUDED_PREVIEW` 两值，看上去 168 个类型似乎仍在分母里。本阶段做了实测核对：

- `extension-protected-nested.csv` 的 168 个 `type_binary` 与 `coverage-baseline.csv` 的 3874 个 `type_binary` **交集为 0**；
- 即这 168 个类型**本就不在冻结清单内**（清单只收 public 类型），"排除分母"的裁决在数据上已经落实；
- 因此分母就是 3836 = 3874 − 38（Preview），**不需要再扣 168**。阶段二报告使用的 3836 / 176 口径正确。

该核对已写入 `data/mapping-checks.txt` 与阶段三报告，避免后续重复争论。

---

## 10. 已知局限（阶段四必须补课）

1. **`MODULE_DEFAULT` 的 1336 行没有包级用途论证。** 它们集中在 `java.base` 的结构性依赖（`java.util` 131、`java.lang` 119、`java.io` 84、`java.security` 79、`java.util.concurrent` 73…）。这不是造假，但也**不等于逐类型论证**：阶段四每批开工前必须把该批涉及的包升到 `PKG_FEATURE`。
2. **第二轮候选约 70–120 行仍待裁决**：遗留集合（`Stack`/`Vector`/`Hashtable`/`Dictionary`）、`Calendar` 系（与世界时间双源）、版本化访问器（`SimpleElementVisitor6/7/8/9`）、ImageIO 插件与辅助包（约 50 行，若只调用 `ImageIO.write` 则一个都用不上）。清单见 `review/mapping-audit.md` §5。
3. **场景文本是规划，不是证据。** 尤其 `type-keep` 保留行，`use_scenario` 是「该类型在本功能中承担什么 + 功能锚点」，仍属事前描述；`Implemented`/`Verified` 必须带真实文件行号与扫描锚点。
4. **`Pending` 有变成永久悬置的风险。** 128 行（JDBC 104 + `javax.tools` 24）必须在报告 §九 9.2 内裁决，不允许带到阶段四之后仍不处置。
5. **设备相关项只能部分验证**（音频、图像）：以「无设备时的降级行为」作为证据，而不是以"本机有混音器"作为证据。
