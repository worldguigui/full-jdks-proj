# 阶段三阶段报告：API 与游戏功能逐类型映射

> 提交日期：2026-11-20
> 对应任务书：§6「阶段三 API 与游戏功能映射」、§9「每个阶段结束时提交」
> 输入：`docs/phase1/data/coverage-baseline.csv`（**只读，未改动**）+ `docs/phase2/game-design.md`（v3 确认版）
> 产出：`docs/phase3/data/api-mapping.csv`（3874 行）、`package-plan.csv`、`module-plan.csv`、`mapping-summary.md`、`mapping-checks.txt`、`env-probe.txt`
> 方法：`docs/phase3/mapping-strategy.md`；工具：`tools/Phase3Mapper.java`、`tools/EnvProbe.java`
> 状态：**待用户审阅**；本阶段不编码，阶段四（M0）尚未启动

---

## 一、本阶段做了什么

1. **建立判定规则层**：177 个包的默认判定（状态 / fc 模块 / 游戏系统 / 批次 / 主使用类 / 原因码 / 场景）+ 类型级覆盖规则（`java.awt` 的 GUI 排除、SecurityManager 体系、遗留 RMI/SAX1、模块归属修正等）。
2. **展开到冻结清单的每一行**：3874 行全部给出状态、场景、预期代码位置、证据占位与排除理由（第一轮曾以「无 Pending」为指标，第二轮复核后废弃了这一指标，见 §二）。
3. **做了一次环境小样实验**（`tools/EnvProbe.java`）来支撑"环境限制"类排除，而不是凭"CLI 用不到"下判断：无头图形、剪贴板（`HeadlessException`）、桌面集成（`false`）、打印服务（2 个 PDF 虚拟打印机）、音频混音器（10 个）、MIDI 音序器（可用）、ImageIO（PNG/TIFF 可用）、字体（112 族，CJK 可显示）。
4. **核对了一个阶段一遗留口径疑问**：`extension-protected-nested.csv` 的 168 个 protected 嵌套类型与冻结清单**交集为 0**——它们本就不在清单内，分母 3836 = 3874 − 38 无需二次扣减。"排除分母"的裁决在数据上已落实。
5. **做了一次现实性校验**：阶段二预估的覆盖率目标与逐类型核算不符（见 §三）。
6. **第二轮质量复核（用户审阅后）**：针对「包级规则替代类型级论证」的问题，逐包重审 `fc-extensions`（712 行）、JDBC 三簇（104 行）、`java.nio.channels`（56 行），并逐条复核 265 条 `ENV`/`DEPRECATED` 排除理由。引入 `Pending` 状态、新增 `rules/type-keep.tsv`（按功能写保留清单）与 `rules/feature-anchors.tsv`（功能锚点）。结果见 §二 与 `review/mapping-audit.md`。

---

## 二、核心统计（复核后）

| 指标 | 第一轮 | **复核后** |
|---|---|---|
| 冻结清单行数 | 3874 | 3874（177 包 / 20 模块） |
| 其中 Preview（`EXCLUDED_PREVIEW`） | 38 | 38（固定 `Excluded`/`PREVIEW`） |
| 覆盖率分母 | 3836 / 176 / 20 | **3836 / 176 / 20（未动）** |
| `Planned` | 2442 | **1684** |
| `Pending`（待设计，新增状态） | 0 | **128** |
| `Excluded`（分母内） | 1394 | **2024** |
| 类型级规划覆盖率 | 63.66% | **43.90%**（1684/3836） |
| 包级规划覆盖率 | 71.02%（125/176） | **48.86%**（86/176） |
| 完整包覆盖率 | 63.07%（111/176） | **39.20%**（69/176） |
| 模块级规划覆盖率 | 80.00%（16/20） | **35.00%**（7/20） |

**排除原因分布**（分母内 2024 行）：`BIZ` 1950（96.3%）／`DEPRECATED` 54（2.7%）／`ENV` 20（1.0%）。
`ENV` 大幅下降不是环境变好了，而是**把「业务无关」误标成「环境限制」的 203 行改回 `BIZ`**（打印 135、LDAP 24、桌面集成 30、`java.awt.print` 10、`datatransfer` 数据模型 4）；剩下 20 行才是真有运行时证据的（`datatransfer` 系统剪贴板 8 + `javax.script` 12）。

**批次日分布**（`Planned`，按首次实现批次）：M0 445 / M1 174 / M2 167 / M3 9 / **M5 704** / M6 185。

**fc 模块计划量**：`fc-persist` 408、`fc-world` 372、`fc-cli` 276、`fc-config` 169、`fc-history` 54（另有 Pending 104）、`fc-audit` 112、`fc-sim` 117、`fc-testkit` 46、`fc-runtime` 23、`fc-extensions` **103**（原 712）、`fc-economy` 4。

**论证强度**（`Planned` 1684 行）：

| 论证来源 | 行数 | 占比 | 含义 |
|---|---|---|---|
| `TYPE_AUDITED` | 120 | 7.13% | 逐类型给出过用途 |
| `PKG_FEATURE` | 228 | 13.54% | 只有包级功能锚点 |
| `MODULE_DEFAULT` | 1336 | 79.33% | 只有模块级锚点（多在 `java.base` 的结构性依赖上） |

> 这张表是本次复核最有价值的一栏：**它把「统计上覆盖了」和「逐类型论证过」分开了**。阶段四每批开工前，该批涉及的包必须从 `MODULE_DEFAULT` 升到 `PKG_FEATURE` 以上。

---

## 三、与阶段二承诺的偏差（复核后）

| 指标 | 阶段二承诺 | 第一轮测算 | **复核后** |
|---|---|---|---|
| 类型级 | 65–72% | 63.66% | **43.90%** |
| 包级 | ≥80% | 71.02% | **48.86%** |
| 完整包 | — | 63.07% | **39.20%** |
| 模块级 | ≥90% | 80.00% | **35.00%** |

偏差分两段，性质不同：

**第一段（−10pt 左右）：阶段二的估值没做逐包核算。** AWT GUI 实际横跨 11 包共 274 个类型、`javax.print` 4 包 135 个类型、JAAS/LDAP 93 个、废弃遗留 54 个，阶段二都只做了定性判断。这部分在第一轮报告里已经承认。

**第二段（−20pt）：第一轮自己也把「包级理由」当成了「类型级论证」。** 典型是 `fc-extensions` 的 712 行——全模块只挂了一句「扩展与模组基础设施（工程）」，包内类型被整体继承；其中 RMI/JNDI/TLS/证书/XML 签名/HTTP/常量描述符等 609 个类型，问「它对应哪个玩家动作」时答不上来。同理还有 `java.nio.channels` 的套接字通道、`java.sql` 三簇。

这两段加起来说明一件事：**在这个「单机 CLI 星际经营」的设计下，能诚实地用起来的 Java SE 类型大约是 44%，而不是 63%，更不是 80%。**

---

## 四、覆盖率口径：需要你选一条（需裁决）

### 4.1 三条路

| 方案 | 覆盖率 | 含义 | 代价 |
|---|---|---|---|
| **A. 接受 43.90% 为新基线**（建议） | 类型 43.9% / 包 48.9% / 完整包 39.2% / 模块 35% | 「全量 API」从目标降为边界：覆盖率是结果，不是任务；M5 从 704 行按功能实际需要推进，M5c 取消或并入 M6 | 数字难看；任务书的 80% 目标正式记录为不可达 |
| **B. 反向补设计** | 有望回到 55–60% | 给 RMI/JNDI/TLS/证书 补真实功能（多人观战、签名 mod、目录式资源索引） | 与「不要为用而用」正面冲突；这些功能要真写、真验收 |
| **C. 混合** | A + 逐项回补 | 只回补你认可的方向（如签名 mod → `java.security.cert` 52 行；本地多人观战 → `java.rmi` 41 行），其余按 A | 每项回补都要在 `type-keep.tsv` 改一行 + 补功能锚点 |

### 4.2 已被否决的上修项（第一轮曾提出，按你的裁决关闭）

| 项 | 结论 |
|---|---|
| A 打印 / PDF 导出（+4 包 / +135 类型） | **不采用**：`javax.print` 135 行保留在分母中，按 `BIZ` 记录为排除，不包装成环境限制 |
| B 剪贴板导出 | **不采用** |
| C Swing Tree/Table 模型 | **不采用**；`javax.swing.undo` 的 `UndoManager` 计划保留 |
| D LDAP 名称解析 | **不采用**：不为 `javax.naming` 自造目录服务 |

---

## 五、关键决策与理由（含复核后的改判）

| # | 决策 | 理由 |
|---|---|---|
| 1 | 用「规则表 + 生成器」而非手写 3874 行 | 判据一致、可复现、可校验；改口径只改一行 TSV，统计自动同步（详见 mapping-strategy） |
| 2 | 分母不动：3836 / 176 / 20 | 任务书 §5.3：不得为覆盖率篡改分母；复核只改状态，不动分母 |
| 3 | ~~本阶段不保留任何 `Pending`~~ **已废弃**：引入 `Pending` 并实际使用 128 行 | 把"技术上做不到"或"功能未设计"硬判成 `Planned`/`Excluded` 是**假二选一**；`Pending` 既不进覆盖分子也不进排除，才不会污染两个方向 |
| 4 | Preview 38 行无条件 `Excluded` | 任务书 §3.3/§7 禁止 Preview 用于正式实现；等价能力用正式 API |
| 5 | 域类型（`Ship`/`Colony`/`Agent`/`Link`）**不映射到 JDK 类型** | 映射表单位是 JDK 类型；自有领域模型不计入覆盖率（任务书 §4.4） |
| 6 | 音频降级为**可选反馈系统**：`sampled` 25 + `midi` 14 = **39**（原 62 全部 Planned） | 「有声音功能」不足以证明 62 个类型都有用：去掉麦克风采集、硬件端口、Reverb、Soundbank、SysEx、设备枚举与设备间转发；无设备时静默 |
| 7 | 打印（`javax.print` 135）判 `Excluded`/**`BIZ`**（原 `ENV`） | 没有打印产品需求；本机存在 PDF 虚拟打印机不是采用理由，也不能把"没有需求"包装成"环境限制" |
| 8 | 剪贴板按类型拆分：系统剪贴板 8 行 `ENV`，数据模型 4 行 `BIZ` | `DataFlavor`/`Transferable` 不依赖系统剪贴板，无头环境可用；只有 `Clipboard` 等真受环境限制 |
| 9 | SecurityManager / AccessController 体系判 `Excluded`/`DEPRECATED` | JEP 411 已 forRemoval；权限边界改用目录权限 + 命令白名单 + `ObjectInputFilter` 白名单 |
| 10 | `javax.swing.undo` 9 类型保留 `Planned` | `UndoManager`/`CompoundEdit` 是纯模型类；撤销范围限定为**尚未提交的玩家计划**，不回滚已推进的世界时间 |
| 11 | `java.awt` 包级 `Planned` + 逐类型排除 99 个 | 无头绘图子集（`Graphics2D`/`Shape`/`Paint`/`Stroke`/`Font`/`Image`/`BufferedImage`）确实用于 PNG 星图；容器/布局/焦点类无用 |
| 12 | 归属按「主使用点」，同类型只计一次 | 任务书 §4.4；`fc-fleet`/`fc-colony`/`fc-science`/`fc-diplomacy` 统计为 0 是"统一世界规则"的必然结果 |
| 13 | 预期代码位置给到「包级主使用类」 | 一个类通常同时使用同包十余个类型；逐类型造文件名是假精度，阶段四回填实际位置 |
| 14 | 把 M5 拆成 M5a/M5b/M5c 的建议写进报告而不改表 | 表里的 `batch` 是"首次实现批次"，拆分是排期问题，不改映射语义 |
| 15 | **`fc-extensions` 712 → 103**；每个功能必须有一条玩家可见的验收标准 | 该模块此前只有一句包级理由，609 个类型答不上"对应哪个动作"；现按 `type-keep.tsv` 逐包写保留清单 |
| 16 | **JDBC 三簇 104 行改判 `Pending`** | JDK 不附带 JDBC 驱动（实测 0），零依赖下无法连接；改为 XPath/Stream 还是允许第三方库未定，故不是 `Planned` 也不是 `Excluded` |
| 17 | 每个 `Planned`/`Pending` 行必须挂**功能锚点** | 生成器硬断言；锚点 = 一条玩家可见验收标准（`feature-anchors.tsv`），防止再次出现"包级理由当类型级论证" |

---

## 六、排除项论证要点（复核后）

- **Swing / AWT GUI（1062 类型）**：纯 CLI 是设计要求。`java.awt` 只取无头绘图子集，探针证明 headless 下 `BufferedImage`+`Graphics2D`+ImageIO 全链路可用。
- **`javax.print`（135）**：无打印产品需求（`BIZ`）；报告与星图导出已由 XML/XSLT/PNG 覆盖。第一轮标 `ENV` 是错标，已改。
- **剪贴板（12）**：8 行 `ENV`（真受无头限制）+ 4 行 `BIZ`（数据模型可用但无需求），见 §五-8。
- **网络设施（`java.net` 60、`java.net.http` 22、`javax.net.ssl` 45、`java.nio.channels` 39）**：单机离线游戏没有端点；离线优先也保证结果可复现，不依赖外部服务。
- **RMI（41）/ JNDI（78）/ XML 签名（58）/ 证书（52）**：无多人观测、无目录服务、完整性用 HMAC + 摘要链、mod 用白名单 + 摘要校验。这四项是本次削减的主体，也是"技术手段支配产品设计"风险最高的地方。
- **`javax.script`（12）**：`ENV`——JDK 21 无任何内置引擎（实测工厂数 0），零依赖下不可用。
- **`java.lang.constant`（12）/ `java.lang.runtime`（2）**：服务字节码生成或由编译器内部引用，源码层不出现；计入"使用"属统计造假。
- **`java.lang.instrument`（6）**：运行时字节码增强需要极强的运维需求；审计改为源码 + classfile 静态扫描。
- **JAAS / Kerberos / SASL / GSS（69）**：无认证域与票据协商；加密存档走 JCA + 口令派生，不需要 `LoginContext`。
- **SecurityManager 体系与其它废弃 API（54）**：`forRemoval` 或已有正式替代（SAX2、动态代理、`Flow`、`PropertyChangeSupport`）。

---

## 七、阶段四排期提示（复核后）

| 批次 | 计划类型数 | 说明 |
|---|---|---|
| M0 骨架 | 445 | `java.lang`/`java.util`/`java.math`/`java.time` + 测试框架 + CLI 壳 |
| M1 星域与舰队 | 174 | 配置 XML/XSD 装载（`fc-config`）为主 |
| M2 主体与事件 | 167 | `java.util.concurrent`、`java.util.random`、`java.time.chrono` |
| M3 经济与殖民 | 9 | 复用 M0 的 `java.math`；仅 `javax.swing.undo` 新增 |
| **M5 持久化与外围** | **704**（原 1264） | 建议仍拆三批：M5a 存档/加密/本地化（`fc-persist` 约 400）、M5b 编年史查询与星图导出（`fc-history` + `fc-cli` 约 270）、M5c 扩展（`fc-extensions` 103，其中 JMX 48 + 音频 39 + 装载 8 + URI/打开 8） |
| M6 审计与打磨 | 185（原 383） | `javax.annotation.processing`/`lang.model` 112、模块装配 23、其余为 M5 复用 |

**排期风险已显著下降**：M5+M6 从 1647（67%）降到 889（53%）；`fc-extensions` 从 712 降到 103，"API 陈列"的结构性风险基本消除。**新风险反过来**：削减后 M5c 只剩 103 行，需要确认它还值不值得单独成批（建议并入 M5b）。

**核心玩法优先原则（用户裁决，写死在这里）**：

1. M0–M4 必须先形成完整核心循环（探索、航行、船员自主、资源生产消耗交易、势力自主演化、玩家行动改变世界、时间推进产生连续变化），核心循环未跑通之前不建扩展基础设施；
2. 每个外围模块必须绑定真实游戏动作（JMX→`> diag`、音频→事件音效、图像→星图、XML→配置与报告、编译→规则），只建接口 + 注册对象不算完成；
3. 每批验收必须回答「玩家多了什么能力 / 世界多了什么行为」，而不是「用了多少类型」。

---

## 八、映射表怎么用于阶段四/五

1. **阶段四每批开始**：从 `api-mapping.csv` 过滤 `batch=Mx`，作为该批 API 清单；实现时若落到别的类/文件，回填 `expected_location` 的实际值。
2. **每批结束**：把该批已真实使用的行改为 `Implemented`，`evidence` 填 `<文件>:<行> <使用形式>`；**不允许**未使用先标 `Implemented`。
3. **阶段五**：`fc-audit` 源码/classfile 扫描结果与 `Implemented` 行比对，改为 `Verified` 或打回；`Excluded` 行逐条复核原因是否仍成立（例如若 M5 真做了打印导出，A 项相关行需改回 `Planned`）。
4. **批次准入（复核新增）**：每批开工前，把该批涉及的包在 `feature-anchors.tsv` 补 `package:` 锚点，使 `justification` 从 `MODULE_DEFAULT` 升到 `PKG_FEATURE` 以上；锚点里写的是**玩家可见的验收标准**，不是"用了哪些类型"。
5. **规则改动必须走生成器**：任何状态调整先改 TSV 再重跑，保证统计、逐包/逐模块表与映射表始终一致。

---

## 九、裁决记录与仍需决定的事项

### 9.0 已裁决（2026-11-20，用户审阅后）

| 裁决项 | 结论 | 落实位置 |
|---|---|---|
| 覆盖率目标 | **有条件接受**严格方案指标，但明确是**规划目标、非验收门槛**，且以源码审计的 `Verified` 为准 | §四 4.1；复核后实际值下降，需重新确认基线（见 9.2） |
| 打印 / PDF 导出（上修项 A） | **不采用**；135 行保留在分母，按 `BIZ` 记录为排除 | `package-rules.tsv`；§六 |
| 上修项 B / C / D | **一律不采用**（剪贴板导出、Swing 模型、LDAP 名称解析） | `package-rules.tsv`、`type-keep.tsv` |
| Swing `UndoManager` | **保留**，但撤销范围限定为尚未提交的玩家计划，不回滚已推进的世界时间 | §五-10；`UNDO-PLAN` 锚点 |
| M5 拆分 | **采用** M5a / M5b / M5c，且 M5c 必须排在核心玩法稳定之后 | §七 |
| 音频 62 类型 | **逐类型复核**，不整体通过 → 收紧为 39 | `type-keep.tsv`（`javax.sound.*`） |
| 外围模块 | 核心玩法稳定后再实现；每项必须绑定真实游戏动作；每批验收回答「玩家多了什么能力」 | §七 末段 |

### 9.1 环境状态（已闭环，无需裁决）

原「JDK 21 运行环境缺失」一项已解除，记录在此以免复查：用户提供实际路径 `E:/jdks/ms-21.0.11`（Microsoft Build of OpenJDK 21.0.11+10-LTS），取代文档中已失效的 `D:/Java/ms-21.0.13.0.2`。已在该 JDK 上完成三项复核，证据见 `data/verification/jdk-version-equivalence.md`：

- **基线等价性**：用 `ApiInventoryScanner` 重扫，得 3887 类型 / 178 包，与 `api-baseline.csv` 逐行比对——缺失 0、额外 0，`module`/`package`/`kind`/`nesting`/`deprecated`/`for_removal`/`preview` 字段差异 0。故 21.0.11 与 21.0.13.0.2 的公开 API 面一致，阶段一冻结数据继续有效，无需重做。
- **生成器一致性**：`Phase3Mapper` 在 21.0.11 下重跑，`api-mapping.csv` / `package-plan.csv` / `module-plan.csv` / `mapping-summary.md` 四份产物的 MD5 与 JDK 17 下完全一致。
- **环境探测刷新**：`env-probe.txt` 已由 21.0.11 重新生成。与 17.0.10 相比仅 `java.version`/`vendor`/`java.home`、`availableLocales`（1017→1069）、`ImageIO` 格式名顺序三项变化；**能力结论不变**（headless=true、剪贴板 HeadlessException、Desktop=false、打印 2 个虚拟服务、混音器 10、PNG/TIFF 可写），故基于**环境能力**的判定无需调整（第二轮后 `ENV` 从 211 降到 20，原因是把「业务无关」改标 `BIZ`，与环境结论无关，见 §二）。

环境层面**仅剩客观约束**（非缺陷）：headless 与"打印仅有 PDF 虚拟服务"是事实，若启用上修项 A，必须带降级路径（无服务时导出 PNG/纯文本）。

**构建工具现状（非阻塞）**：本机无全局 `mvn`，但 `~/.m2/wrapper/dists/` 内已有 Maven 3.9.16 发行版可在 JDK 21 上运行；`~/.m2/repository` 仅 705 KB，首次构建需联网（Maven Central 可达）。建议 M0 阶段在仓库内引入 Maven Wrapper，构建命令固定为 `JAVA_HOME=E:/jdks/ms-21.0.11 ./mvnw ...`，不依赖 PATH 与全局安装。

---

### 9.2 仍需你决定

1. **覆盖率基线选 A / B / C**（见 §四 4.1）。复核后为 43.90% / 48.86% / 39.20% / 35.00%，与原先批准的 ≥63% / ≥70% / ≥62% / ≥80% 不再一致，需要你明确新基线或指定回补方向。
2. **JDBC 三簇（104 行）如何定**：改用 XPath/Stream 查询（→ 改判 `Excluded`/`BIZ`），还是允许第三方嵌入式库（→ 改回 `Planned`，但破坏零依赖约束）。当前状态 `Pending`。
3. **`javax.tools`（24 行）是否进入设计**：若要做玩家规则编译，需先定规则语言子集、安全边界（禁止反射/IO）、诊断呈现方式；否则改判 `Excluded`。
4. **§5 的第二轮候选（约 70–120 行）**：遗留集合/`Calendar`/版本化访问器/ImageIO 插件等，是否在本阶段一并改判，还是留到对应批次。

---

## 十、风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 覆盖率从 63.66% 降到 43.90% 被误读为"退步" | 信任成本 | 不是退步，是把虚高部分摘出去：758 行经不起「对应哪个玩家动作」一问；根因与逐包裁决见 `review/mapping-audit.md` |
| 复核后核心包仍有 1336 行只有模块级论证 | 阶段四可能边写边想 | 每批开工前必须把该批涉及的包升到 `PKG_FEATURE` 以上并写清包级用途；§5 候选清单随批裁决 |
| M5c 削到 103 行后"批次意义"变弱 | 排期形式主义 | 建议 M5c 并入 M5b；是否保留独立批次由排期决定，不影响映射语义 |
| `javax.tools` / JDBC 长期停在 `Pending` | 悬而未决 | §九 9.2 要求本轮裁决：改判或补设计，不许无限期挂起 |
| 音频/打印等设备相关项只能部分验证 | 证据不足 | 能力探测 + 降级路径 + 黄金文本测试；无设备时以"降级行为"作为证据 |
| 场景文本是规划而非事实 | 阶段五证据不足 | `Implemented`/`Verified` 必须带文件行号与扫描锚点，规划文本不作证据 |
| ~~JDK 21 缺失~~（已解除） | 曾导致阶段四无法开工 | 已落位 `E:/jdks/ms-21.0.11`；重扫与冻结基线逐行一致，生成器重跑产物哈希不变 |
| 运行环境版本与阶段一留痕不一致（21.0.11 vs 21.0.13.0.2） | 复查时被误判为数据漂移 | 等价性证据归档于 `data/verification/`；阶段一文件保持冻结不改写，差异只在新文档中说明 |
| protected 嵌套口径再起争议 | 反复讨论 | 已实测交集为 0，写入 `mapping-checks.txt` 与本报告 §一-4 |

---

## 十一、下一阶段计划

**阶段四：分批实现（M0 → M6）**，前置条件与顺序：

1. **前置**：确认覆盖率新基线（§九 9.2-1）、JDBC 与 `javax.tools` 去留（9.2-2/3）；运行环境已就绪（`E:/jdks/ms-21.0.11`，§9.1）。
2. **M0 骨架**：15 模块骨架、`fc-world` sealed 模型、`Stardate`、`fc-testkit`、`fc-config` 装载骨架、CLI 壳、`next` 空转；验收：`next 30` 跑通、`mvn dependency:tree` 无第三方依赖。
3. 之后按 §七 的批次推进，每批结束更新映射表状态并跑 `fc-audit`。
4. **阶段五**：以源码/classfile 扫描为主做最终审计，输出三指标与已/未覆盖清单。

---

## 十二、本阶段交付清单

| 文件 | 内容 |
|---|---|
| `docs/phase3/data/api-mapping.csv` | **3874 行全量映射表**（23 列：模块/包/类型/种类/废弃/预览/protected/状态/fc 模块/游戏系统/批次/场景/预期位置/证据/原因码/原因/备注） |
| `docs/phase3/data/package-plan.csv` | 177 包逐包规划（分母数、Planned/Excluded、覆盖类型 FULL/PARTIAL/NONE/PREVIEW_ONLY、原因分布） |
| `docs/phase3/data/module-plan.csv` | 20 模块逐模块规划（类型级/包级覆盖率、触及包数） |
| `docs/phase3/data/mapping-summary.md` | 统计摘要（自动生成） |
| `docs/phase3/data/mapping-checks.txt` | 自检证据（行数、集合比对、原因分布、覆盖率） |
| `docs/phase3/data/env-probe.txt` | 环境能力探测原始输出（Excluded/ENV 的证据；已由 JDK 21.0.11 重新生成） |
| `docs/phase3/data/verification/runtime-capability-probe.md` | 脚本引擎 0 / JDBC 驱动 0 / ImageIO 格式清单（`javax.script` 与 `java.sql` 判定的硬证据） |
| `tools/CapProbe.java` | 上述取证脚本 |
| `docs/phase3/data/verification/jdk-version-equivalence.md` | JDK 21.0.11 落位与冻结基线等价性复核（重扫逐行比对、生成器哈希、环境结论不变） |
| `docs/phase3/data/verification/jdk21-rescan/` | 21.0.11 重扫原始产出（`api-baseline.csv` / `packages.csv` / `load-failures.csv`），不与阶段一目录混放 |
| `docs/phase3/mapping-strategy.md` | 判定规则、状态机（含 `Pending`）、原因码、归属规则、复现与校验方法、已知局限 |
| `docs/phase3/review/mapping-audit.md` | **本轮质量复核报告**：可信度边界、逐包裁决、五个复核点逐条回答、第二轮候选、覆盖率三选一 |
| `docs/phase3/rules/type-keep.tsv` | **新增**：按功能写「需要哪些类型」，包内其余一律排除 |
| `docs/phase3/rules/feature-anchors.tsv` | **新增**：功能锚点与玩家可见验收标准 |
| `docs/phase3/phase3-report.md` | 本文件 |
| `docs/phase3/rules/package-rules.tsv` | 177 包默认判定（人工维护） |
| `docs/phase3/rules/type-overrides.tsv` | 类型级覆盖规则（人工维护） |
| `tools/Phase3Mapper.java` | 映射生成器 + 硬断言校验 |
| `tools/EnvProbe.java` | 环境能力探测 |

> 阶段一冻结数据未做任何修改（`docs/phase1/` 只读）；阶段二设计文档因复核结论受影响的部分已列入 §九 9.2，等你裁决后再改。请先给出 9.2 的四项决定，我再进入阶段四 M0。
