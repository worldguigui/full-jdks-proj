# full-jdks-proj 项目长期记忆

## 项目
Frontier Chronicle（边境纪事）：Java SE 21 全量标准 API 星际远征 + 舰队经营 + 自主世界模拟游戏（见 README）。
**目标已变更为 B（API 覆盖优先，游戏作为承载载体）**：以最大化 Java SE 21 标准 API 功能性使用为首要目标，游戏为统一应用载体；允许主动设计 API 驱动功能（远程管理/认证信任/命名发现/规则编译/运行监控/数据交换签名/多格式输出/开发测试工具）。
**阶段调整**：阶段三=API 机会盘点 → 阶段四=类型级准入与论证 → 阶段五=分批实现 → 阶段六=最终覆盖审计；阶段间须用户确认，**本阶段不启动 M0**。

## 环境
- **JDK 21 实际路径：`E:/jdks/ms-21.0.11`**（Microsoft Build of OpenJDK 21.0.11+10-LTS，用户 2026-11-20 提供）。文档原约定的 `D:/Java/ms-21.0.13.0.2` 已不存在；`JAVA_HOME` 仍指向 `D:/app/Java`（17.0.10），**调用时须写全路径，勿依赖 PATH**。
- 等价性已实测：21.0.11 重扫结果与阶段一冻结基线**逐行一致**（3887 类型/178 包，缺失 0/额外 0/字段差异 0，Deprecated 68、Preview 38、load failures 0）→ 冻结数据与阶段三映射无需重算。证据 `docs/phase3/data/verification/jdk-version-equivalence.md` + `jdk21-rescan/`。
- **构建工具**：本机无全局 `mvn`；`~/.m2/wrapper/dists/apache-maven-3.9.16-bin/*/apache-maven-3.9.16/bin/mvn` 可用且以 JDK 21.0.11 运行。`~/.m2/repository` 仅 705KB（首次构建需联网，Central 可达 200）。阶段四建议加 Maven Wrapper（3.9.16），构建命令写成 `JAVA_HOME=E:/jdks/ms-21.0.11 ./mvnw ...`。
- `Phase3Mapper` 在 17 与 21 下重跑产物 MD5 完全相同（api-mapping `e5de231a…`、package-plan `e3c5e561…`、module-plan `56dbfed7…`、summary `ad546d3d…`）。
- Shell 为 Windows Git Bash；中文输出偶发 GBK 乱码，文件一律 UTF-8。

## 阶段一核心数据（已冻结，复用勿重扫）
- **冻结清单边界**：20 SE 内容模块 / 177 包 / 3874 类型（用户 2026-11-20 裁决）。宽口径含 java.smartcardio：178/3887（JAVA_PREFIX_ONLY）。
- **覆盖率计算分母**：3836 类型 / 176 包 / 20 模块 = 3874−38(Preview) / 177−1(java.lang.foreign 整包纯 Preview)。任务书 §3.3 派生，见 api-inventory.md §0.2。
- 冻结裁决：smartcardio（13）、protected 嵌套（168）、Preview（38）排除分母但保留扩展清单；Deprecated 68（forRemoval 36）继续纳入。
- 预览 38、@Deprecated 68、SE 21 无 record 类型（仅版本统计结论；record 语言特性 Java 16 JEP 395 定稿）。
- 数据：`api-baseline.csv`（11 列，只读基准）；`coverage-baseline.csv`（14 列，**阶段三在其上扩 mapping_status**，3874 行 = 3836 IN_DENOMINATOR + 38 EXCLUDED_PREVIEW）；`extension-*.csv`（13/38/168）；`freeze-manifest.md`（SHA-256）。
- 工具：`tools/FreezeBaseline.java`（冻结，硬断言）。全量差异审计证据 `data/verification/`（缺失 168 / 额外 0 / 无法判断 0）。
- java.smartcardio：官方 API 文档按 java.* 前缀分组收录，但**不属于 JSR 396 §7 标准模块集合**；不得称 JDK 专有 API。

## 阶段二（2026-11-20 重写 + v3 审查修订，**已确认**）
- 方向以 README Prompt 为准：星际远征 + 舰队经营 + 自主世界模拟；**旧的聚落经营版 game-design.md 已作废重写**，现为 **v3 审查修订版（745 行，20 章 + 附录）**。
- 核心架构：统一世界规则（Location/Link/Agent/Stock+ResourceLedger/WorkUnit 五抽象 + 单一 Stardate + 单一 Chronicle），星域与殖民地是同一套规则的不同尺度（任务书硬要求）。
- v3 关键修正：sealed 根与全部 permitted 类型收敛 `fc-world`（JPMS 同模块约束），`Ship` 是移动 `Location`；拆 `WorldState`（world）/`GameLoop`+Tick（sim）/`Chronicle` 接口（world）+实现（history）；新增 `fc-config` → **15 个 fc- 模块**；配置 M0/M1 落地。
- 事件分两层：Tick 内确定性同步队列，`Flow` 仅 tick 后投递；RNG 流状态入档；审计改为源码/classfile 扫描为主。
- 覆盖率目标按逐包核算：31 包 / 1089 类型排除 → 包级 ≥80%（上限 82.4%）、类型级 65–72%（上限 ≈72%）、模块级 ≥90%；`Excluded` 不改分母。
- 初始规模：1 星域 / 16 地点 / 4 势力 / 3→8 船 / 36 船员 / 1 殖民地 / 12 类资源（11 货物 + credits，credits 仅记账本）。
- `docs/phase2/phase2-report.md` 为 v3 阶段报告；用户 2026-11-20 确认，进入阶段三。
- `.gitattributes` 固定 `eol=lf`；`freeze-manifest.md` 已注明 SHA-256 基于 LF 内容（非数据漂移）。

## 阶段三 v3：目标重构 + 准入体系升级（2026-11-20）
- 用户指令：项目目标改 **B（API 覆盖优先）**；新增 feature_origin（CORE/API_DRIVEN/INFRASTRUCTURE/DEVELOPER_TOOLING）、justification_level（L0-L4）、anchor_type（PLAYER/OPERATOR/DEVELOPER）；**Planned 必须 L3 逐类型论证，L0/L1 不产出 Planned，L2→Pending，L4→Verified**；允许设备/Provider 依赖但须可选运行+降级；标准 API 与第三方严格区分；14 个 API 方向须逐项九项盘点。
- **数据模型**：`api-mapping.csv` 26 列（type_name/feature_origin/feature_anchor/anchor_type/justification_level/justification/api_role/runtime_requirement/fallback_strategy/test_evidence/implementation_location 等）。规范 `docs/phase3/admission-spec.md`；目标/原则 `docs/phase3/project-direction.md`。
- **规则层重构**：`feature-anchors.tsv` 7 列（id/name/origin/anchor_type/acceptance/verification/priority，废弃 scope 隐式绑定改显式 feature_id）；`type-keep.tsv` 10 列（package/feature_id/types/api_role/runtime/fallback/justification/level/rest_code/rest_reason），表达"功能↔类型准入"，**不再包级默认 Planned**。
- **生成器重写**（tools/Phase3Mapper.java，约 1000 行）：12 条硬校验（Planned 须 L3+锚点+api_role+位置；Pending 记录未决；Excluded 标注[已探索]/[未探索]；ENV 须证据；双向完整性无孤立锚点）。判定顺序：Preview→protected→type-overrides→type-keep(清单命中/rest排除)→package-rules(包级 Planned 降 L1 Pending)。
- **重评结果**：Planned 1684→**1544**、Pending 128→**839**（L2 候选）、Excluded 2024→**1453**；类型级 43.90%→**40.25%**，潜在上限 **62.12%**（Pending 全升 L3）。ENV 清零（脚本/剪贴板转 API_DRIVEN L2 候选）。L1 清零。
- **14 方向盘点**（`api-opportunity-report.md`）：RMI/JNDI/TLS/证书/XML签名/JDBC/规则编译/注解处理/Instrumentation/打印/剪贴板 11 方向纳入（L2 候选），Swing 仅 undo、AWT 仅无头绘图，LDAP 建议排除。释放 753 类型进 Pending。
- **待裁决**：①JDBC 驱动策略（自研 vs 第三方 vs 弃用）；②规则编译安全边界；③LDAP 子包去留；④JMX monitor/timer 时间模型；⑤Swing table/tree 维持排除。**待人工审阅，不启动 M0**。

## 阶段三 v4.1：全模块覆盖落实（2026-11-20）
- 用户：剩余未覆盖模块全部覆盖，聊天室 TCP+RMI 两者配合、WebSocket 服务端可第三方（不计入覆盖），其余按全局设计自行设计。
- 7 个模块核心类型升 L3（+242）：java.compiler（javax.tools/annotation.processing/lang.model …14 全 L3，…6/7/8/9 历史访问器保持 L2）、datatransfer(12)、instrument(6)、management.rmi(remote 18+rmi 9)、scripting(12)、sql.rowset(30)、xml.crypto(58)。
- **数字**：Planned 1671→**1913**、Pending 978→735、Excluded 1187→1188；类型级 **49.87%**、包级 61.93%、完整包 46.59%、模块级 **90%(18/20)**、潜在上限 69.03%。
- **仅剩 2 个未覆盖模块**：java.security.jgss(16，可选 Kerberos/GSS 需 KDC，L2)、java.transaction.xa(3，探索资格，Excluded)——都是产品边界性质，非技术做不到。
- 报告 `docs/phase3/full-coverage-report.md`。未启动 M0。

## 阶段三 v4：项目定位升级 + 12 模块采纳 + 联机系统（2026-11-20）
- 用户：项目定位升级为「Full JDK 21 Project——可扩展星际模拟与联机管理平台」；12 个模块全部进入功能设计范围；多人联机（聊天室）进路线图；**严格区分模块采纳≠功能锚点≠类型 L3**；新增「本地可信≠远程可信」安全边界。
- 新增 `docs/phase3/online-system-design.md`：三阶段联机（聊天室→远征协同→多人世界）；技术选型：TCP Socket+RMI 核心、WebSocket 可选客户端（java.net.http 只带客户端不带服务端）、TLS 加密、SASL 认证、Kerberos/GSS 可选。
- **四模块重审**：java.rmi(28 核心升 L3：Remote/UnicastRemoteObject/LocateRegistry/Registry/异常家族)、java.net.http(20 升 L3：HttpClient/WebSocket)、java.security.sasl(6 升 L3：Sasl/SaslServer/SaslClient)、java.security.jgss(16 重开 KERBEROS-AUTH L2)。
- 新增 feature：CHAT/REMOTE-AUTH/KERBEROS-AUTH。package-rules 补 SASL/GSS/Kerberos 的 fc-extensions 归属。
- **数字**：Planned 1617→1671、Pending 1006→978、Excluded 1213→1187；类型级 43.56%、包级 50.57%、模块级 **55%(11/20)**、潜在上限 69.06%。未覆盖模块 12→9。
- 未覆盖 9 模块：compiler/datatransfer/instrument/management.rmi/scripting/jgss/sql.rowset/xa(探索)/xml.crypto，全部已进设计范围（有 feature+清单），待阶段四 L3。
- 报告 `docs/phase3/online-expansion-report.md`。待裁决：①联机传输选型(Socket vs RMI 主通道)②WebSocket 服务端是否第三方③XA 保持探索④Kerberos 纯可选。未启动 M0。

## 阶段三补充二：五项决策落地 + 未覆盖模块清单（2026-11-20）
- 用户五项决策：①JDBC=核心自研只读 CSV Driver（不选 H2/SQLite，runtime 改 NONE）；②规则编译=本地可信 Mod 充分权限、不承诺进程内沙箱；③Swing=正式立项（模型层/视觉层分阶段审计，priority P3→P2）；④JMX 时间=现实/游戏分离形成正式规范；⑤fc://=统一资源命名体系（解析/注册/生命周期语义）。
- 新增两份正式规范：`docs/phase3/time-model-spec.md`（两时钟源：Stardate 可注入 Clock + 现实时间 System.nanoTime，硬约束互不越界）、`docs/phase3/naming-spec.md`（fc://<scope>/<path> 格式，解析/注册/生命周期 + JNDI 类型归属 + LDAP 维持排除）。
- 交付 `docs/phase3/decisions-and-uncovered.md`：**12 个未覆盖模块清单**（Planned=0）——java.compiler(136)/datatransfer(12)/instrument(6)/management.rmi(9)/net.http(22)/rmi(55)/scripting(12)/security.jgss(16)/security.sasl(10)/sql.rowset(30)/transaction.xa(3)/xml.crypto(58)，各附包构成与现状，供用户自行设计功能。
- 数字不变：Planned 1617/Pending 1006/Excluded 1213；类型级 42.15%、潜在上限 68.38%。未启动 M0。

## 阶段三补充：API 覆盖机会扩张（2026-11-20）
- 用户裁决：JDBC=自研只读 CSV Driver；Compiler=受信任模组编译；JNDI=真实命名上下文；JMX=现实时间与游戏时间分离；Swing=独立可选桌面管理客户端。本轮进一步扩大 API 功能使用集合。
- **新增能力簇**：ADMIN-DESKTOP（桌面管理客户端，Swing+AWT 模型类 112，L2 候选）、AUTH（JAAS 可选登录 36）、REMOTE-WATCH 传输层（java.net 套接字 56 + nio.channels 通道 39，重开 L2）。
- **升 L3**：JDBC 核心接口 30（CSV 查询，可选第三方 Driver，接口类型计入/Driver 实现不计入）、JNDI 命名核心 30、JMX monitor/timer 13（System.nanoTime 现实时间，与 Stardate 分离）。
- **新增 `rules/api-exploration.tsv` 探索台账**（56 条，8 列）：追踪每个未纳入类型探索过程，四态 UNEXPLORED/OPPORTUNITY_FOUND/FEATURE_DESIGNED/TYPE_JUSTIFIED；生成器读取校验 + summary 输出分布。
- **重评数字**：Planned 1544→**1617**、Pending 839→**1006**、Excluded 1453→**1213**；类型级 40.25%→**42.15%**、潜在上限 **68.38%**、模块级 30%→**40%**（8/20）。
- **技术约束落地**：不自研 JDBC 驱动换覆盖（用第三方 Driver）；Compiler≠沙箱；不以 SecurityManager 为基础；脚本 API 与 Provider 区分；Swing/AWT 逐类型审计不整包纳入；设备能力探测降级。
- **待裁决**：①JDBC Driver 选型（H2 CSV/SQLite）；②规则编译信任模型；③Swing 桌面客户端是否立项；④JMX 时间分离规范文档化；⑤命名上下文 fc:// 语义。**未启动 M0**。

## 阶段三 v2：用户审阅后的质量复核（2026-11-20）
- 用户结论：四项裁决可定（打印/剪贴板/Swing 模型/LDAP 上修一律不采用；`javax.swing.undo` 保留但只管未提交计划；M5 拆 M5a/b/c 且 M5c 排后），但**映射表需先过质量复核再进 M0**。核心质疑：包级规则冒充类型级论证、`fc-extensions` 712 过大、音频 62 全过、`datatransfer` 的 ENV 理由不严谨、"无 Pending"不等于论证充分。
- **复核做法**：新增 `rules/type-keep.tsv`（按功能写"需要哪几个类型"，包内其余一律排除）+ `rules/feature-anchors.tsv`（每个 Planned/Pending 必须挂一条玩家可见验收标准）。生成器加 3 列：`feature_anchor`/`feature_name`/`justification`（TYPE_AUDITED / PKG_FEATURE / MODULE_DEFAULT），并硬断言"Planned/Pending 必须有锚点"。
- **结果**：Planned 2442→**1684**、Pending 0→**128**、Excluded 1394→**2024**；类型级 **43.90%**、包级 48.86%、完整包 39.20%、模块级 35.00%。`fc-extensions` **712→103**（JMX 48/音效 25/氛围乐 14/装载 8/URI 6/打开 2）。
- **Pending 用法（重要）**：只用于"技术上做不到"或"功能未设计"——JDBC 三簇 104（JDK 无驱动，实测 0）、`javax.tools` 24（规则语言与安全边界未定）。Pending 不进分子也不进排除。
- **原因码纠错**：203 行 ENV→BIZ（打印 135、LDAP 24、桌面 30、`awt.print` 10、`datatransfer` 数据模型 4）；ENV 只剩 20（系统剪贴板 8 + `javax.script` 12）。`datatransfer` 按类型拆分 8 ENV / 4 BIZ。**不得把"没有需求"包装成"环境限制"**。
- **硬证据**：`tools/CapProbe.java` → `data/verification/runtime-capability-probe.md`：`ScriptEngineManager` 工厂 0、`DriverManager.getDrivers()` 0、ImageIO 标准 writer 覆盖 png/tiff。
- **论证强度（阶段四准入门槛）**：逐类型审计 120 / 包级锚点 228 / 仅模块级 **1336**（java.util 131、java.lang 119、java.io 84、java.security 79、concurrent 73…）。每批开工前必须把该批涉及的包升到 PKG_FEATURE 以上。
- 文档：新增 `docs/phase3/review/mapping-audit.md`（可信度边界、逐包裁决、五点逐条回答、第二轮候选 ~70–120 行、覆盖率 A/B/C 三选一）；重写 `phase3-report.md` §一–§七、`mapping-strategy.md` §2/§3/§10；README v0.0.4。
- **待用户裁决**：①覆盖率基线 A(接受 43.9%)/B(反向补设计)/C(混合回补)；②JDBC 三簇去留（XPath 还是允许第三方库）；③`javax.tools` 是否进设计；④第二轮候选是否本阶段改判。**未获确认前不启动 M0**。

## 阶段五 M0（2026-11-20 已实现，验收通过）
- 15 模块 Maven 骨架 + fc-world sealed 模型 + fc-testkit + fc-config + GameLoop/Cli/Main。M0 三项验收全过（next 30/依赖树/坏配置报错）。
- 构建：`JAVA_HOME=E:/jdks/ms-21.0.11 ~/.m2/wrapper/dists/apache-maven-3.9.16-bin/*/bin/mvn`（无全局 mvn，用 wrapper dist）。运行 `java -cp 各模块target/classes fc.runtime.Main`。Git Bash classpath 用 `;`。
- Windows System.out 默认 GBK，Main 已显式 UTF-8 输出。PropertiesConfig 支持文件路径+类路径。
- 报告 `docs/phase5/m0-report.md`。下一步 M1。

## 阶段五 M1（2026-11-20 已实现，验收通过）
- fc-config：WorldDefinition + XmlWorldLoader（DOM+XSD+语义校验/XXE 防护）+ world.xsd + default-world.xml（16 地点/16 航线/3 船）。
- fc-sim：WorldAssembler（定义→图+账本+产出+parents）；tick 三阶段 Time/Movement/Inventory + TickPipeline；GameLoop 逐日跑管线。
- fc-fleet：Navigation（Dijkstra 无向）+ VoyageService（校验+扣油+事件）。
- fc-world：Ship 受控可变；LocationOps.withStock（sealed+模式匹配）；LocationGraph.replace/ships/placeCount；WorldState.parents；GameEvent.ShipDeparted/ShipArrived。
- fc-cli：map/ships/sail + StarMapRenderer。
- 验收 4 项全过（配置驱动 16/16/3、换配置即换世界、往返 1→4→1 燃料 120→100、10 天 Deneb Farm food 40→70）+ 13 单测。脚本 `tools/m1-verify.sh`。
- 报告 `docs/phase5/m1-report.md`。下一步 M2。
- **用户要求 M1 起不再写验收脚本**：已删 m0/m1 verify 脚本；改为 shade 打包 `fc-runtime/target/frontier-chronicle.jar`，`java -jar` 直接开玩。后续交付以「能直接玩」为准。
- 已加 `chronicle [N]` 命令 + help 开局引导。

## 阶段五 M2（2026-11-21 已完成）
- 十二阶段管线（3-faction / 9-market 为显式占位）；统一主体三角色；Needs/Assignment/Trait；觅食迁徙（Foraging BFS≤2跳）。
- RngStreams：L64X128MixRandom + splitmix 派生 weather/events/agents/market/factions；world.xml `seed`（默认 20261120）；同种子历史逐字节一致。
- EventQueue(tick 内有序) + ChronicleStage 唯一落库 + EventBus(Flow, tick 后只读广播)。
- CLI 新增 `crew`；next 附带回合报告；事件渲染带人名地名。
- 测试 24 个通过。报告 `docs/phase5/m2-report.md`。下一步 M3（经济贸易）。

## 约定
- 游戏代码模块前缀 `fc-`，Maven 多模块，零第三方 compile/runtime 依赖，测试用自研 fc-testkit。
- 每完成一个阶段向 `docs/phaseN/` 交付 md + 数据；扫描/审计工具放 `tools/`。
