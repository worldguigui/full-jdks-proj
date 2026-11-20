# Frontier Chronicle（边境纪事）—— 星际远征游戏完整设计（阶段二 · v3 审查修订版，待用户确认）

> 状态：**正式设计稿（v3），等待用户确认后进入阶段三**（确认前不做 API-功能映射、不编码）。
> v3 修订（2026-11-20）：按审查意见修正 sealed/模块边界、`Ship` 位置模型、`World`/配置依赖方向、配置装载排期、覆盖率目标、Tick 内事件确定性、存档 RNG 状态、审计方案与若干命名/口径一致性问题；**未改动阶段一冻结数据与证据**。
> 前置：**阶段一已于 2026-11-20 正式冻结**——`docs/phase1/api-inventory.md`（冻结版）；正式清单 `docs/phase1/data/coverage-baseline.csv`（3874 行 / 177 包 / 20 模块；覆盖率计算分母 3836 类型 / 176 包）。冻结裁决：smartcardio 与 protected 嵌套排除分母并保留扩展清单；Deprecated 68 个继续纳入；38 个 Preview 类型排除分母、单独登记。
> 运行环境：Microsoft Build of OpenJDK **21.0.13.0.2**，Windows / Linux / macOS，纯 CLI，零第三方运行时/编译依赖。
>
> **版本沿革**：v1「聚落经营」稿已作废；v2 按 2026-11-20 修订后的任务书「星际远征 + 舰队经营 + 自主世界模拟」整体重写；**v3 为审查修订版**，项目名沿用 Frontier Chronicle（边境纪事）。
> **本阶段边界**：只完成游戏整体设计。任务书 §6 阶段二明确「不要提前进行逐个 Java SE API 类型的功能映射」——本版 §15 仅给出域级覆盖策略与排除预判，**逐类型映射表属于阶段三**。

---

## 1. 游戏概述

### 1.1 一句话定位

你是失联远征舰队的总指挥：带领一支小型舰队在未知星域「赫利俄斯断片」中找燃料、跑贸易、建前哨、挖遗迹、与四个自主势力周旋，让几十名船员和一个殖民地在**同一套世界规则**下活下去，并把这一切写成可以查询的《边境纪事》。

### 1.2 世界观与初始背景

- **裂隙事件**：母文明「环带联合体」的远征舰队经不稳定曲率通道「裂隙」进入未知星域；通道随即坍缩，与后方补给线失联。玩家是远征组织「边境署」的总指挥，旗舰为「纪事号」。
- **星域**：「赫利俄斯断片」是一块被异常空间撕裂的星域碎片，星系间只有少数稳定航线，局部存在「静默区」（通讯/传感衰减）。星域中散布先驱文明「织星者」的遗迹与异常现象，是科研与长期目标的核心线索。
- **开局**：3 艘飞船（1 艘勘探护卫舰、1 艘货船、1 艘工程船）、36 名船员、1 个刚落地的前哨站「锚点-01」、启动资金与 6 个已知地点；其余 10 个地点为未知。燃料仅够约 20 回合的常规航行，食物约 40 回合——**生存压力从第 1 回合就存在，首条补给线必须在约 20 回合内打通**。
- **势力**：金秤商会（贸易集团）、新赫利俄斯公社（独立殖民地）、断片巡防队（地方政权）、静默群（未知文明，初期只以异常信号间接呈现）。四者都有自己的资源、目标、关系网与行为倾向。

### 1.3 设计支柱

1. **一个世界，一种规则**：星域尺度与殖民地尺度复用同一套位置、主体、资源、生产、事件与时间机制（§2），不是两个拼接的子游戏。
2. **世界自己会动**：船员、居民、势力都是带需求与目标的自主主体；玩家推进一回合，世界沿一条时间轴自结算。
3. **玩家是总指挥，不是操作员**：玩家做的是航线、编组、预算、建设、科研与外交决策；具体干活由主体自己完成。
4. **历史可查询**：航行、发现、事故、交易、外交、生平都进入编年史，可按时间/地点/人物/主题检索，甚至用 SQL 与 XML 导出。
5. **命令行即界面**：信息分层、叙事化回合报告、ASCII 星图；命令围绕**游戏动作**组织（§13），不围绕 Java API 分类组织。
6. **游戏优先**：先有完整玩法与世界规则，再谈 API；任何 API 的引入必须回答「它为哪个玩法服务」。

### 1.4 玩家长期追求的目标与发展阶段〔重点问题 1〕

**长期目标（双终局，玩家用行动自然选择）**：
- **归途**：修复「织星者」星门遗迹，重建稳定回航通道，把远征档案送回母文明。
- **扎根**：建成自持的星际殖民地与区域秩序，将《边境纪事》刻入信标，宣告断片星域成为新家园。

两条终局共用同一套前置科技与建设链，但资源投向与外交姿态不同（归途偏科研/探索，扎根偏生产/外交），形成真正的策略分歧，而非结尾弹窗选项。

| 阶段 | 回合（约） | 玩家追求 | 解锁/判定 |
|---|---|---|---|
| D0 生存 | 1–30 | 建立燃料/食物正循环，摸清周边 6 个地点 | 首条稳定补给线；不再净消耗 |
| D1 立足 | 30–100 | 前哨站升为殖民地，签下首条贸易线 | 殖民地 1 级；声望达到「知名」 |
| D2 扩张 | 100–250 | 打通多星系航线网、建立第二前哨站、科研 Tier 2 | 舰队 ≥5 艘；已知地点 ≥12 |
| D3 自主 | 250–500 | 殖民地自持、势力格局成型、遗迹线索串联 | 殖民地净产出为正；至少 1 个盟约 |
| D4 终局 | 500+ | 星门 / 纪事信标（二者择一，取决于长期投入） | 终局事件 + 编年史封卷 |

**失败条件**：旗舰损毁且无可用指挥舰、组织资不抵债持续 N 回合、或殖民地与舰队全部失去生存能力。**节奏**：单局约 300–600 回合（D4 终局通常在 500 回合后进入），单回合结算 < 100ms（初始规模）。

### 1.5 游戏的核心决策〔重点问题 2〕

核心决策是**稀缺资源在四种用途之间的分配**，且伴随信息不对称与不可逆后果：

`推进（探索/航行）` ⟷ `生存（船员/补给）` ⟷ `增长（建设/生产）` ⟷ `知识（科研）`

三个典型两难：
1. 最后一船燃料：深入静默区追查遗迹信号（可能解锁关键科技），还是保住金秤商会的贸易线（稳定现金流）？
2. 唯一的高级工程师：派去维修受损货船（保住运输能力），还是驻留殖民地建造反应堆（解锁 Tier 2 生产）？
3. 巡防队高价委托：接单可缓解资金但会激怒公社（关系不可逆下滑），还是保持中立错失窗口？

决策之所以成立，是因为：资源是**共享账本**（§2.3）、船员是**有状态且会疲劳/拒绝的主体**、世界状态**持久改变**（§10）、未知区域**信息不完备**（迷雾）。

---

## 2. 统一世界规则：一个世界，不同尺度〔重点问题 7 的核心答案〕

任务书明确要求「星域模拟和殖民地模拟应当是同一套世界规则下的不同尺度，而不是两套互不相关的模拟器」。本设计以**五个统一抽象 + 一条时间轴 + 一部历史**实现，尺度差异只是**实例化参数**。

### 2.1 五个统一抽象

| 抽象 | 定义 | 星域尺度实例 | 殖民地尺度实例 |
|---|---|---|---|
| `Location`（sealed） | 一切可容纳主体与库存的地点节点 | `StarSystem` / `Planet` / `OrbitalStation` / `Anomaly` / `Ship`（移动位置，含货舱与舰上人员） | `Colony` / `Facility`（农场、反应堆、工坊、居所） |
| `Link`（sealed） | 连接两个 Location 的通行边，含时间/风险/成本/容量 | `WarpRoute`（航线） | `SurfaceRoute` / `InternalLane`（地表/站内物流路径） |
| `Agent`（统一主体循环） | 有需求、技能、关系与经历的行为主体；`Crew` / `Resident` / `Envoy` 是**角色**，不是独立类 | 船员 Agent（角色 `CREW`，在舰船上）、使者 Agent（角色 `ENVOY`，势力代表） | 居民 Agent（角色 `RESIDENT`，在设施中） |
| `Stock` + `ResourceLedger` | `ResourceLedger` 是权威守恒账本；`Stock` 是某 `Location` 的账本分区/视图；`ResourceKind` 为 11 种货物枚举（信用点只进财务 `Ledger`） | 货舱、空间站仓库、星球矿点 | 殖民地仓库、设施缓冲仓 |
| `WorkUnit`（`Recipe` 驱动） | 输入资源 + 技能 + 设施 + 时长 → 输出资源/进度；可有多个 Agent 贡献工时 | 采矿、飞船维修、科研项目 | 农业、加工、能源生产、建筑施工 |

**关键**：`Crew` / `Resident` / `Envoy` 不是三套类，而是同一个 `Agent` 的不同角色（`AgentRole`）、`Assignment` 与 `currentLocation`；`Ship` 是 `Location` 的移动子类型，因此「船员在船上」与「货物在船上」用同一位置/账本模型；`WarpRoute` 与 `InternalLane` 不是两套运输系统，而是同一 `Link` 的不同 `kind`；「星际贸易」与「殖民地内部调拨」都是沿 `Link` 移动 `ResourceLot` 的 `Transfer`。

> **模块约束**：`Location` / `Link` / `GameEvent` 及其全部 permitted 类型（含 `Ship`、`Colony`、`Facility`、七个事件子接口与事件记录）统一定义在 `fc-world` 同一 JPMS 模块内；未命名模块下则必须在同一包。领域模块只提供行为/服务，**禁止在 `fc-fleet` / `fc-colony` 等领域模块中定义新的 sealed 子类**。

### 2.2 尺度差异只是参数（对照）

| 维度 | 星域尺度 | 殖民地尺度 | 共用机制 |
|---|---|---|---|
| 距离/时间 | 航线 3–12 回合 | 内部路径 0–1 回合 | `Link.travelTime` |
| 风险 | 离子风暴、劫掠、导航误差 | 设备故障、环境灾害 | `Link.risk` + 事件引擎 |
| 生产 | 前哨站采矿、飞船维修 | 农业/加工/能源 | `Recipe` + `WorkUnit` |
| 市场 | 星系市场（含势力税率） | 内部配给市场 | 同一 `PriceModel` |
| 主体 | 船员（岗位：驾驶/工程/科研/医疗） | 居民（岗位：耕作/加工/医护/管理） | 同一 `Agent` 决策循环 |
| 信息 | 迷雾（未知/已勘察/已勘探） | 设施状态可见度 | 同一 `Knowledge` 位图 |

### 2.3 单一时间轴与单一事件史

- 只有一条 `Stardate`（启程历）与一条 `Tick` 管线（§8）；不存在「星域日历」与「殖民地日历」。
- 只有一部 `Chronicle`；所有尺度的 `GameEvent`（sealed 层次）写入同一索引（时间/地点/人物/主题/原因/过程/结果）。
- 只有一份 `ResourceLedger` 家族；跨尺度转移通过 `Transfer` 记账，保证经济守恒可验证。

### 2.4 明确禁止的反模式

- ❌ 为星域写一套 `FleetSimulator`、为殖民地另写一套 `ColonySimulator`，各自维护数值与日期；
- ❌ 船员与居民使用两套需求/技能/关系模型；
- ❌ 生产、市场、事件在两套尺度中各实现一遍；
- ❌ 系统之间只通过「回合末统一加数值」耦合，而不是通过共享领域对象与事件因果链交互（§7）；
- ❌ 把 sealed 的 `Location` / `Link` / `GameEvent` 根放在一个模块、把 permitted 子类放进另一个模块（JLS 不允许；命名模块须同模块，未命名模块须同包）。

---

## 3. 初始世界规模与扩展能力〔重点问题 8〕

### 3.1 初始规模参数（严格收敛，任务书 §2.5 参考值）

| 项 | 初始值 | 理由 |
|---|---|---|
| 可探索星域 | **1 个**（赫利俄斯断片） | 不模拟整个银河系 |
| 地点 | **16 个**（6 已知 + 10 未知），含 4 类地形/环境模板 | 足以覆盖探索、贸易、建设、科研全部交互 |
| 其他势力 | **4 个**（3 个可见 + 1 个初期仅异常信号） | 足以形成关系网与外交压力 |
| 玩家舰队 | **1 支**，初始 3 艘、上限 8 艘 | 编组/维修/物流有意义但不失控 |
| 飞船类型 | **5 种**（勘探/货运/工程/科研/护卫） | 功能定位差异，非数值换皮 |
| 船员 | **36 名**（可招募至约 60） | 个体状态 + 关系网可计算 |
| 前哨站/殖民地 | **1 个**（锚点-01，可升 3 级） | 单尺度验证统一规则 |
| 资源种类 | **12 类**（11 种货物：燃料/能源/食物/水/矿物/金属/材料/设备/药品/奢侈品/科研数据 + **信用点**） | 货物进 `ResourceKind`/`Stock`；信用点仅进财务 `Ledger`，不得双记账 |
| 系统 | 航行/贸易/探索/时间推进 **完整可用** | 任务书要求的最小完整闭环 |

**规模红线**：不得为容纳更多 API 增加地点、势力或资源种类；新增内容必须由玩法需求驱动，且通过配置数据加入（§3.2），不修改核心规则代码。

### 3.2 数据驱动与惰性生成

- 地点、配方、事件、势力性格、科技树、商品价格弹性**全部由 XML/配置定义**。**配置模型与 XML/XSD 装载器放入新模块 `fc-config`（依赖 `fc-world`），在 M0/M1 就位**；M5 只增加加密存档、本地化、版本迁移与 mod 配置。核心代码只实现规则，不硬编码 16 个地点。
- 未知地点的详细内容在**首次抵达时惰性生成**（种子化），保证存档体积与回合耗时不随潜在世界规模增长。

### 3.3 扩展点与路线图

| 扩展点 | 扩展方式 | 是否改核心 |
|---|---|---|
| 新地点类型 / 新异常 | 实现 `ExtensionLocation`（`non-sealed`，由 sealed `Location` 许可）+ 配置条目 | 否（只新增实现，不修改 sealed 根；核心一次性 permit `ExtensionLocation`） |
| 新势力 | 配置定义目标/性格/初始资源 | 否 |
| 新配方 / 新科技 | `Recipe` 与科技树配置 | 否 |
| 新事件 | 实现 `ExtensionEvent`（`non-sealed`）+ 事件表/条件/权重配置 + 可选处理器 | 否（只新增实现，不修改 sealed 根） |
| 新飞船设备 | 设备定义 + 效果修饰器 | 否 |
| 世界规模扩大 | 提高配置中的地点数（星域分片仍复用同一 `LocationGraph`） | 否 |

> 常规扩展只需配置实例化**已有**类型；只有新增类型才使用 `ExtensionLocation` / `ExtensionEvent` 两个 non-sealed 扩展口。修改 sealed `permits` 本身属于核心变更，不作为 mod 扩展方式。

核心玩法验证（M2 结束）之后才允许提升规模上限；每次提升须通过确定性重放与经济守恒测试（§16）。

---

## 4. 十六大系统设计

> 每个系统给出：职责 / 关键对象 / 与其他系统的接口 / 自主性与演化点。系统按**游戏边界**划分，不按 Java API 分类划分。

### 4.1 星域与空间结构系统
- **职责**：维护 `LocationGraph`（16 节点 + `Link` 边）、坐标、天体结构、环境参数、迷雾。
- **对象**：`StarSystem` / `Planet` / `OrbitalStation` / `Anomaly` / `Ship` / `Colony` / `Facility`（sealed `Location`；定义均在 `fc-world`）；`WarpRoute` / `SurfaceRoute` / `InternalLane`（sealed `Link`）。
- **接口**：为航行（4.7）、探索（4.8）、生产（4.6）、事件（4.11）提供位置与通行成本；向 CLI（4.13）输出星图。
- **演化**：环境承载/资源再生（§8.4）、静默区范围随剧情波动、新地点惰性生成。

### 4.2 舰队与飞船管理系统
- **职责**：飞船类型、设备槽、能源/燃料/货舱/耐久、舰队编组、维修与升级。
- **对象**：`Ship`（`ShipType`、`ShipModule`、`Hull`、`CargoHold`；`Ship` 是 `Location` 的移动子类型，`parent()` 指向当前停靠/所在位置）、`Fleet`、`ShipyardOrder`。
- **接口**：为航行提供可用船与载重；为船员提供岗位；消耗资源账本；维修走 `WorkUnit`（与殖民地使用同一 `Recipe`）。
- **演化**：磨损随航行累积、设备老化触发故障事件、改装结果持久改变飞船能力。

### 4.3 船员自主行为模拟系统
- **职责**：统一 `Agent` 循环在船员上的实例——身份/属性/技能/需求/岗位/关系/经历。
- **对象**：船员 Agent（角色 `CREW`；`SkillSet` 驾驶/工程/科研/医疗/贸易/探索、`Needs`、`Trait`、`RelationGraph`、`Biography`）、`Assignment`。**`Crew` 是角色名，不是独立类。**
- **接口**：向生产/航行/科研输出有效工时与技能系数；产生个人事件写入编年史；消耗食物/休息资源。
- **演化**：疲劳与情绪影响效率、共事演化友谊/冲突、技能随任务成长、可能拒绝指令、伤病与寿终写入生平。

### 4.4 殖民地与前哨站经营系统
- **职责**：`Colony` / `Facility` 的建设、升级、人口、生产与消费——**与星域共用同一 `Location`/`Agent`/`WorkUnit`/`Stock`**。
- **对象**：`Colony`（1→3 级）、`Facility`（农场/反应堆/工坊/居所/实验室/仓库）、居民 Agent（角色 `RESIDENT`，与船员同类型 `Agent`）。**`Resident` 是角色名，不是独立类。**
- **接口**：设施挂载 `Recipe`、居民被分配 `Assignment`、内部物流走 `InternalLane`、市场用同一 `PriceModel`；向科研提供实验室产能。
- **演化**：人口自然增长/流失、设施损耗与维护、环境承载限制扩张、政策（工时/配给）影响士气与产出。

### 4.5 时间与回合推进系统
- **职责**：单一 `Stardate`、Tick 管线（§8）、确定性随机、批推进与事件中断。
- **对象**：`Stardate`（自定义历法）、`TickPipeline`、`RandomStreams`（按子系统派生）。
- **接口**：驱动全部系统的结算顺序；向 CLI 提供 `next [n]`；向存档提供时间锚点。
- **演化**：季节/星象周期、环境变化、势力行动节奏均由同一时间轴驱动。

### 4.6 资源生产、消耗和贸易系统
- **职责**：11 种货物资源 + 信用点的 `Recipe` 生产、消耗、市场定价、财务（收入/支出/债务/利润）。
- **对象**：`ResourceKind`（11 种货物）、`Stock`（Location 的账本视图）、`ResourceLedger`（权威守恒账本）、`Recipe`、`WorkUnit`、`PriceModel`、`Ledger`（财务，BigDecimal）。
- **接口**：`ResourceLedger` 是唯一权威账本，`Stock` 只能通过账本变更；`Transfer` 实现跨尺度物流并在账本两端记账；信用点只进入 `Ledger`，不进 `Stock`/`ResourceKind`；`PriceModel` 受供需、运输成本、势力税率、事件冲击影响。
- **演化**：价格波动、矿点衰减与再生、商路季节性、通胀/债务利息。

### 4.7 航行、运输与物流系统
- **职责**：航线规划、在途状态、运输成本与风险、自动补给线。
- **对象**：`RoutePlan`（多段 `Link`）、`Voyage`（在途 `Ship` + 进度 + 事件队列）、`Transfer`（货物/人员）、`SupplyLine`。
- **接口**：依赖 `LocationGraph` 与 `Ship`；在途事件由事件系统注入；到达后触发市场/任务/编年史。
- **演化**：航线风险随天气/势力冲突变化；重复通行提升航线熟悉度（时间/风险下降）。

### 4.8 地图与探索系统
- **职责**：迷雾三态、勘察/勘探队列、发现物与异常现象、星图呈现。
- **对象**：`Knowledge`（位图）、`SurveyMission`、`Discovery`（遗迹/矿脉/隐泉/异常/新地点）、`StarMapRenderer`（ASCII + 无头 PNG）。
- **接口**：探索结果改变 `LocationGraph`（新增节点/边）、解锁科技碎片、生成发现事件。
- **演化**：未知区首次抵达时惰性生成；发现永久改写世界状态并写入编年史。

### 4.9 科研与技术发展系统
- **职责**：技术树（Tier 1–3）、研究项目进度、解锁能力、遗迹线索。
- **对象**：`TechNode`、`ResearchProject`（本身是一个 `WorkUnit`）、`TechEffect`（修饰器）、`Artifact`（科研数据）。
- **接口**：消耗「科研数据 + 实验室产能 + 科研船员」；解锁后修饰 `Recipe` / `Link` / `Ship` / `Facility` 参数（例如航线时间 −20%）。
- **演化**：科技效果**真实改变世界规则参数**（§7 因果链起点），而非只给一个成就标记。

### 4.10 势力、外交与关系系统
- **职责**：4 个势力的资源/目标/关系网/行为倾向；玩家外交动作与后果。
- **对象**：`Faction`（`Disposition`、`Goal`、`Assets`、`RelationMatrix`）、`DiplomaticAction`、`Treaty`、`Envoy`（`Agent` 实例）。
- **接口**：势力控制部分市场与税率、发布委托（4.11）、影响航线风险；玩家动作改变关系并触发势力反应事件。
- **演化**：势力自主扩张/迁徙/贸易/结盟/冲突，彼此之间也有关系变化——世界不围绕玩家转。
- **冲突与威胁结算（最小机制）**：`threaten` / 护航 / 劫掠 / 势力冲突不做战术棋盘，而走**事件化冲突结算**：以舰队战力、船员技能、声望、关系与当前补给计算风险值，产出托付金、货损、船体耐久损失、关系变化与后续 `ConflictEvent`；全部结果写入编年史并可重放。这样既保留玩法后果，又不引入独立战斗子系统。

### 4.11 事件与任务系统
- **职责**：事件层次（sealed + record，全部定义在 `fc-world`）、条件触发/加权抽样/连锁因果、委托任务、玩家待决选项。
- **对象**：`GameEvent`（`WorldEvent` / `FleetEvent` / `CrewEvent` / `ColonyEvent` / `MarketEvent` / `DiplomacyEvent` / `DiscoveryEvent` + `ExtensionEvent`）、`GameEventQueue`（Tick 内确定性有序队列）、`Commission`、`Decision`；`Flow` 只用于 Tick 后的报告/遥测通知，**不参与模拟状态写入**。
- **接口**：Tick 内事件按固定顺序进入队列并被各系统处理；产生后续事件（因果链）；重大事件附 2–4 个决策选项；全部写入编年史。Tick 后如需异步通知，使用 `Flow`/`SubmissionPublisher`，但必须与模拟线程隔离，且不改变 `WorldState`。
- **演化**：事件结果持久改变世界（损失、关系、价格、科技线索），并成为后续事件的条件。

### 4.12 世界历史与日志系统
- **职责**：编年史（时间/地点/参与者/原因/过程/结果 + 索引）、舰队日志、人物生平、运行日志。
- **对象**：`Chronicle`（追加型 + 倒排索引）、`Biography`、`System.Logger` 后端（环形缓冲 + 文件轮转）。
- **接口**：CLI 查询与分页；阶段四提供只读 JDBC 驱动、XML 互导、XSLT 报告；存档包含编年史分片。
- **演化**：历史是查询对象，也是成就/终局判定与势力 AI 的输入（势力会「记住」玩家行为）。

### 4.13 命令行交互系统
- **职责**：命令体系、帮助、面板、报告、输入纠错（详见 §13）。
- **对象**：`CommandParser`、`Panel`、`TurnReport`、`HelpSystem`、`LineEditor`（命令历史 + 行末建议/编号选择；Tab 补全仅在支持的终端 best-effort）、`Replay`（macro）。
- **接口**：只读查询世界状态；写操作经命令总线进入世界；`next` 触发 Tick。
- **演化**：命令历史/宏脚本、上下文提示、错误建议随玩家使用习惯优化。

### 4.14 存档与加载系统
- **职责**：多槽位存档、加密、完整性、原子写、自动存档。
- **对象**：加密 JAR 容器（`world.dat` 对象流 + 编年史分片 + `MANIFEST.MF` 元数据 + SHA-256/CRC32 清单）、`ObjectInputFilter` 白名单、`FileLock` 双开保护、`Preferences` 槽位偏好；**`RandomStreams` 状态**（算法名 + 种子 + 流位置，或种子 + 操作日志）。
- **接口**：序列化统一世界状态（单一时间轴、单一账本、单一编年史、RNG 流状态）；加载后确定性重放一致。**不得依赖 `RandomGenerator` 实现自身的序列化**。
- **演化**：版本迁移、损坏抢救存档、口令加密（PBKDF2 + AES-GCM）。

### 4.15 游戏配置系统
- **职责**：机制数值表（配方/成本/事件权重/科技树）的 XML+XSD 校验装载、用户偏好、本地化。**配置模型/装载器在 `fc-config`（M0/M1 就位）**，`fc-persist` 负责存档、偏好与本地化运行时。
- **对象**：`GameConfig`（`fc-config`）、`Preferences`、`java.util.ResourceBundle`（zh-CN/en-US）、`Collator` / `DateTimeFormatter` / `DecimalFormat`。
- **接口**：启动时为所有系统注入参数；玩家可改数值（mod 第一步）。
- **演化**：配置热校验、版本化配置、Locale 切换不改动世界状态。

### 4.16 测试与验证系统
- **职责**：确定性重放、统一规则一致性、经济守恒、并发压测、存档往返、CLI 快照、构建验证（详见 §16）。
- **对象**：自研 `fc-testkit`（注解 + 反射 runner，零第三方）、`.replay` 脚本、黄金文本、状态哈希。
- **接口**：CI 中每批必跑；审计模块产出覆盖率并与阶段一冻结基准比对。
- **演化**：随批次扩充用例；重放脚本成为回归资产。

---

## 5. 核心玩法循环（任务书 §2.3 七步的落地）

```
┌─────────────────── 玩家决策段（交互式） ───────────────────┐
│ 1 获取信息  scan / map / market / factions / chronicle      │
│ 2 制定计划  选定探索·贸易·建设·科研目标                     │
│ 3 配置资源  fleet / assign / cargo / route / budget          │
│ 4 执行行动  sail / survey / trade / build / research / envoy │
└───────────────────────────┬────────────────────────────────┘
                            │ next [n]
┌───────────────────────────▼ 世界演化 + 结果结算（自主运行） ─┐
│ 5 世界演化：船员/居民/势力/环境/市场 沿同一条 Tick 管线结算  │
│ 6 结果结算：收益·消耗·事件·局势变化 → 编年史落库             │
│      ↓ Tick 内有序事件队列（确定性）→ 回合报告 / 成就 / 遥测   │
│      ↓ Tick 后 Flow 通知（可选，不参与模拟）                   │
└───────────────────────────┬────────────────────────────────┘
                            ▼
        7 调整策略：回合报告 + 待决事件（批推进时中断）
                            │ 循环（D0→D4 / 失败判定在结算末尾）
```

**单回合体验**：读叙事化报告（约 15 秒）→ 调整 1–2 项配置（约 30 秒）→ `next`。待决事件中断批推进，保持「总指挥感」而非「报表操作员感」。

---

## 6. 系统关系图

```
                    ┌───────────────────────────────┐
                    │      CLI 交互 / 回合报告        │
                    │  (命令·面板·星图·帮助·报告)      │
                    └──────────┬──────────▲─────────┘
                   命令/配置 │        │ 报告/待决事件
              ┌──────────────▼──────────┴────────────────┐
              │        统一世界核心（fc-world / fc-sim）   │
              │  LocationGraph · Agent · ResourceLedger   │
              │  WorkUnit(Recipe) · Stardate · GameEvent  │
              │  ── 单一时间轴 Tick 管线 ──               │
              └──┬────┬────┬────┬────┬────┬────┬────────┘
                 │    │    │    │    │    │    │
        ┌────────▼┐ ┌▼────┐ ┌▼────┐ ┌▼───┐ ┌▼────┐ ┌▼───────┐
        │ 舰队/飞船│ │船员 │ │殖民 │ │经济 │ │科研 │ │势力/外交│
        │ 航行物流 │ │Agent│ │前哨 │ │市场 │ │技术 │ │关系/委托│
        └────┬────┘ └┬────┘ └┬────┘ └┬───┘ └┬────┘ └──┬─────┘
             └───────┴───────┴───┬───┴──────┴─────────┘
                                 ▼
                   ┌─────────────────────────────────────┐
                   │ 事件队列（Tick 内确定性）→ 事件/任务 │
                   │ Flow 仅 Tick 后通知（报告/遥测）     │
                   └─┬──────┬──────┬──────┬──────────────┘
                     ▼      ▼      ▼      ▼
              编年史   回合报告  成就/终局  遥测/日志
                     │
                     ▼
        持久层：加密 JAR 存档 · 配置 · 编年史索引/查询(JDBC/XML/XSLT)
        扩展层：mod(SPI+签名) · 宏/运行时编译 · JNDI · 电讯/观战(可选)
```

**读图要点**：所有系统挂在**同一个统一世界核心**上，共享 `LocationGraph` / `Agent` / `ResourceLedger` / `WorkUnit` / `Stardate` / `GameEvent`；不存在两个并列的世界模拟器。

---

## 7. 系统间如何相互影响〔重点问题 3〕

给一条真实可发生的因果链，串起 8 个系统（每个箭头都由共享领域对象或事件触发，而非回合末统一加数值）：

```
科研(4.9) 解锁「高效曲率线圈」
  → TechEffect 修饰 Link.travelTime（星域结构 4.1 / 航行 4.7 参数真实改变）
  → 偏远矿点变得可达，Voyage 增多（舰队 4.2 磨损上升）
  → 矿物供给上升 → PriceModel 价格回落（经济 4.6）
  → 原矿贸易利润压缩 → 玩家转向「加工制造」Recipe（生产 4.6 / 殖民地 4.4）
  → 需要更多工程技能 Agent → 招募与岗位重排（船员 4.3）
  → 殖民地人口需求上升 → 食物/住房压力上升（殖民地 4.4）
  → 触发「配给危机」条件事件（事件 4.11）
  → 势力趁机提出援助委托，关系变化（外交 4.10）
  → 全过程写入编年史，成为后续事件条件（历史 4.12）
```

**再给一条事件穿透示例**（证明不是拼数值表）：一次「离子风暴」`GameEvent` 沿 Tick 管线被逐系统处理——环境（星象/天气）→ 航线风险↑、在途 `Voyage` 减速/受损 → 工程船员紧急维修、疲劳↑ → 备件/燃料超支 → 该星系补给价格↑ → 金秤商会抬价或提出援助委托 → 生成后续「援助决议」待决事件 → 编年史记录时间/地点/参与者/原因/过程/结果。**同一个事件对象**穿过 8 个系统，每步都改变共享世界状态。

**避免世界模拟沦为互不关联的数值结算〔重点问题 7 的补充〕**：
1. 五个统一抽象强制复用（§2），禁止双模拟器；
2. 系统交互**必须**经由共享领域对象（账本/位置/主体/事件），禁止各系统私藏数值表；
3. 事件具备**连锁因果**（事件产生后续事件、结果成为后续条件），不是一次性随机加数值；
4. 每个 Tick 沿依赖 DAG 结算（§8.2），下游读取的是上游刚写入的真实状态；
5. 「统一规则一致性测试」（§16）在自动化层面防止两套实现漂移；
6. Tick 内事件使用**有序确定性队列**，`Flow` 仅用于 Tick 后通知，避免异步投递影响重放。

---

## 8. 时间推进与模拟结算机制〔重点问题 5〕

### 8.1 一条时间轴

`Stardate`：1 Tick = 1 星历日；30 日 = 1 月；4 月 = 1 星域季（受星象周期调制）；自定义历法（正式 API `java.time.chrono` 的 `Chronology` 实现）与 ISO 互转用于存档时间戳。**唯一**时间源，星域与殖民地共享。

### 8.2 Tick 管线（固定 12 阶段，按依赖 DAG 排序）

| # | 阶段 | 主要动作 | 写入的共享状态 |
|---|---|---|---|
| 1 | 时间推进 | `Stardate++`、星象/季节/环境周期、天气采样 | 环境参数 |
| 2 | 环境与生态 | 资源再生、承载力、灾害前兆 | `Location` 环境量 |
| 3 | 势力 AI | 目标评估、贸易/扩张/外交/冲突决策 | `Faction` / 关系矩阵 |
| 4 | 主体感知 | 全体 `Agent`（船员/居民/使者）刷新需求与状态 | `Needs` / 情绪 |
| 5 | 主体决策 | 统一决策循环：需求排序 → 依性格/技能选行动（可能拒绝指令） | `Assignment` |
| 6 | 生产与工作 | 所有 `WorkUnit` 推进（采矿/农业/维修/科研/施工），虚拟线程并发后**固定顺序汇合** | `Stock` / 项目进度 |
| 7 | 航行与物流 | `Voyage` 沿 `Link` 推进、`Transfer` 结算、到达处理 | 位置/账本 |
| 8 | 消耗与维护 | 口粮/燃料/能源、船体与设施磨损、债务利息 | 账本/耐久 |
| 9 | 市场与财务 | 供需→价格、交易结算、组织收支 | `PriceModel` / `Ledger` |
| 10 | 事件引擎 | 条件触发 + 加权抽样 + **连锁因果**；重大事件生成待决选项 | `GameEvent` 队列 |
| 11 | 编年史落库 | 事件索引、人物生平、统计聚合 | `Chronicle` |
| 12 | 报告聚合 | 按严重度排序的回合报告、成就/终局判定 | 输出视图 |

### 8.3 确定性与随机

- 同一种子 + 同一操作序列 ⇒ 同一历史（`.replay` 重放回归的基础）。
- 随机来自 `RandomGeneratorFactory` 的可跳跃算法；按子系统（天气/事件/势力/主体/市场）派生独立流，互不干扰。
- **RNG 状态入档**：保存每个子系统流的算法名、种子与流位置（或保存种子+操作日志重放）；不依赖 `RandomGenerator` 实现自身的 `Serializable` 支持。
- 虚拟线程并发（阶段 6）采用**固定顺序汇合**与领域锁，保证并发不破坏确定性；关键路径用有序集合避免哈希迭代序影响。
- Tick 内事件走**有序确定性队列**；`Flow`/`SubmissionPublisher` 只在 Tick 结束后用于报告/遥测，不参与状态写入。

### 8.4 世界持续演化的具体体现

- 环境：矿点衰减/再生、季节与星象调制、静默区漂移；
- 主体：疲劳/情绪/技能/关系/生平持续变化，生老病死；
- 经济：价格波动、商路兴衰、债务累积；
- 势力：即使玩家不介入也会扩张、结盟、冲突（历史会「记住」）；
- 事件：结果成为后续条件，形成可追溯的世界史，而非每回合重置的随机数。

---

## 9. 自主行为：船员 / 居民 / 势力〔重点问题 4〕

**统一 `Agent` 决策循环**（三类主体共用，仅 `Assignment` / `Location` / `Employer` 不同）：

```
perceive（饥饿·疲劳·伤病·情绪·安全·社交）
  → rankNeeds（生存 > 安全 > 归属 > 尊严 > 成长）
  → decide（性格特质 × 技能 × 岗位指令 × 士气 加权；可能拒绝玩家指令）
  → act（产出 / 消耗 / 移动 / 社交 / 事件）
  → record（个人经历 + 编年史条目）
```

| 主体 | 所在 `Location` | 典型 `Assignment` | 自主性表现 |
|---|---|---|---|
| 船员 Agent（角色 `CREW`） | `Ship` / `Station` | 驾驶、工程、科研、医疗、贸易 | 疲劳时怠工、与同事结怨/结谊、技能成长、请辞或拒绝高危任务 |
| 居民 Agent（角色 `RESIDENT`） | `Facility` / `Colony` | 耕作、加工、医护、管理 | 迁移岗位偏好、家庭与生育、对配给政策反应、参与 colony 事件 |
| 使者 Agent（角色 `ENVOY`） | `Faction` 关联地点 | 谈判、情报、贸易 | 按势力目标主动提议/抗议/勒索 |

> `CREW` / `RESIDENT` / `ENVOY` 是实现同一 `Agent` 类的角色枚举；不得再定义 `Crew` / `Resident` / `Envoy` 子类，否则违反「统一主体」约束。

**势力 AI**：目标驱动（现金流 / 领地 / 科技 / 声望）+ `RelationMatrix` + 经济行为；每 Tick 阶段 3 独立决策，并与其他势力交互（不围绕玩家）。其资源与库存同样挂在统一 `ResourceLedger` 上，因此势力行为会**真实影响**市场价格与航线风险。

---

## 10. 玩家行动如何改变世界〔重点问题 6〕

| 行动类别 | 持久改变的世界状态 | 可观察后果 |
|---|---|---|
| 探索 | 揭开迷雾、新增 `Location`/`Link`、生成发现物 | 星图扩展、新航线/新市场、编年史永久条目 |
| 建设 | `Colony`/`Facility` 升级、产能与人口变化 | 供给曲线改变、价格回落、势力态度变化 |
| 贸易/运输 | 转移库存、改变 `PriceModel` 供需、组织财务 | 市场波动、商路兴衰、势力税收变化 |
| 科研 | `TechEffect` 修饰 `Link`/`Recipe`/`Ship` 参数 | 航线变快、新配方可用、旧策略失效 |
| 外交 | 关系矩阵、条约、税率、航线风险 | 势力自主行为改变（护航/封锁/委托） |
| 冲突/威胁 | 关系矩阵、护航/封锁状态、货损/耐久、`ConflictEvent` | 势力反应、航线风险、委托谈判 |
| 事件决策 | 事件结果成为后续条件 | 连锁事件链、人物生平、终局倾向 |

反例约束：玩家不能「直接给某个数值 +10」；所有影响必须经过共享领域对象或 `GameEvent`，从而可被编年史追溯、被存档恢复、被重放验证。

---

## 11. 核心数据模型（Java 骨架）

```java
// —— 统一标识与值对象（record）——
public record LocationId(long id) implements Comparable<LocationId> {}
public record AgentId(long id) {}
public record ShipId(long id) {}
public record ResourceLot(Map<ResourceKind, BigDecimal> amounts) {}  // 避免与 java.util.ResourceBundle 混淆
public record Stardate(long day) {}                       // 单一时间轴
public record Recipe(List<ResourceLot> inputs, ResourceLot outputs,
                     int ticks, SkillKind requiredSkill, FacilityKind facility) {}

// —— 五个统一抽象：全部定义在 fc-world，尺度差异只是子类型与参数 ——
// 模块约束：sealed 根与所有 permitted 类型必须在同一 JPMS 模块（fc-world）；
// 未命名模块（classpath）下必须在同一包。
public sealed interface Location permits StarSystem, Planet, OrbitalStation,
                                         Anomaly, Colony, Facility, Ship,
                                         ExtensionLocation {
    LocationId id();
    Stock stock();                                       // 账本分区/视图
}
public non-sealed interface ExtensionLocation extends Location {}  // mod 扩展口
public final class Ship implements Location {            // 移动位置：船员 + 货舱 + 在途状态
    LocationId id();            // Location 身份（进入 LocationGraph）
    ShipId shipId();            // 舰队/船员引用
    LocationId parent(); Stock cargo(); VoyageProgress voyage();
}
public sealed interface Link permits WarpRoute, SurfaceRoute, InternalLane {
    LocationId from(); LocationId to();
    int travelTicks(); double risk(); BigDecimal cost();
}
public enum AgentRole { CREW, RESIDENT, ENVOY }
public final class Agent {                               // 船员/居民/使者同一类型
    AgentId id(); AgentRole role(); LocationId location(); Assignment assignment();
    Needs needs(); SkillSet skills(); RelationGraph relations(); Biography bio();
    void tick(WorldView view);                           // 统一决策循环（只读视图）
}
public final class WorkUnit {                            // 生产/维修/科研/施工共用
    Recipe recipe(); int progress(); List<AgentId> workers();  // 支持多人协作
}

// —— 事件：sealed + record + 模式匹配（Java 21 正式语言特性）——
// 七个事件子接口 + ExtensionEvent 均在 fc-world 内；具体记录可在 fc-world 或由配置/SPI 生成。
public sealed interface GameEvent permits WorldEvent, FleetEvent, CrewEvent,
                                          ColonyEvent, MarketEvent,
                                          DiplomacyEvent, DiscoveryEvent,
                                          ExtensionEvent {
    Stardate when(); LocationId where();
    default List<AgentId> who() { return List.of(); }
    default String cause() { return ""; }                // 原因
    default String outcome() { return ""; }              // 过程/结果
}
public non-sealed interface WorldEvent extends GameEvent {}
public non-sealed interface FleetEvent extends GameEvent {}
public non-sealed interface CrewEvent extends GameEvent {}
public non-sealed interface ColonyEvent extends GameEvent {}
public non-sealed interface MarketEvent extends GameEvent {}
public non-sealed interface DiplomacyEvent extends GameEvent {}
public non-sealed interface DiscoveryEvent extends GameEvent {}
public non-sealed interface ExtensionEvent extends GameEvent {}  // mod 扩展口
public record IonStorm(Stardate when, LocationId where, int severity) implements WorldEvent {}
public record Wedding(AgentId a, AgentId b, Stardate when, LocationId where) implements CrewEvent {}
// 分发（含 ExtensionEvent 时必须保留 default 分支）：
String render(GameEvent e) {
    return switch (e) {
        case IonStorm(var d, var loc, var s) when s > 3 -> ...;
        case Wedding(var a, var b, var d, var loc)       -> ...;
        default                                          -> ...;
    };
}

// —— 世界状态根（fc-world）与装配根（fc-sim） ——
public final class WorldState {
    LocationGraph graph;            // 16 星域节点 + Link 边；Colony 为节点，Facility 归属 Colony 子图
    Map<AgentId, Agent> agents;     // 船员 / 居民 / 使者（同一 Agent 类型 + 角色）
    ResourceLedger ledger;          // 权威守恒账本；Location.stock() 是分区视图
    Chronicle chronicle;            // 接口在 fc-world；索引/查询实现在 fc-history
    Stardate now;
}
// fc-sim：TickPipeline / GameLoop 持有 WorldState，不放进 fc-world，避免循环依赖
public final class GameLoop {
    WorldState state;
    TickPipeline pipeline;          // §8.2 十二阶段
}
```

要点：`Colony` / `Facility` 与 `StarSystem` 同为 `Location` 子类型；`Crew` / `Resident` / `Envoy` 同为 `Agent` 实例；跨尺度运输同为 `Link` + `Transfer`——**统一规则在类型系统层面被强制**。

---

## 12. 模块划分（Maven 多模块，零第三方依赖）

| 模块 | 职责 | 依赖 |
|---|---|---|
| `fc-world` | 统一世界规则：`Location`/`Link`/`Agent`/`Stock`/`WorkUnit`/`GameEvent`/`Stardate`/`WorldState` + `Chronicle` 接口；**全部 sealed 根与 permitted 类型同在此模块** | 无（最底层） |
| `fc-config` | 配置模型与装载（JSON/Properties/XML 解析）、校验、默认值与 mod 配置合并；启动即用 | world |
| `fc-sim` | `GameLoop`、Tick 管线、确定性事件队列、随机流、事件分发（Tick 内同步，`Flow` 仅在 tick 后） | world, config |
| `fc-fleet` | 舰队/飞船/设备/航行/运输物流 | world, sim, config |
| `fc-colony` | 前哨站/殖民地/设施/居民岗位 | world, sim, config |
| `fc-economy` | 资源、配方生产、市场定价、财务 | world, sim, config |
| `fc-science` | 技术树、研究项目、遗迹线索、`TechEffect` | world, sim, config |
| `fc-diplomacy` | 势力、关系矩阵、外交、委托任务 | world, sim, config |
| `fc-history` | 编年史索引与查询、生平、只读 JDBC / XML / XSLT 报告 | world, config |
| `fc-cli` | 命令解析、面板、星图、叙事化报告、帮助、行编辑 | 领域模块 + sim（只读视图 + 命令总线） |
| `fc-persist` | 加密 JAR 存档、本地化、偏好（配置读取由 `fc-config` 负责） | world, config |
| `fc-extensions` | mod SPI/签名、宏与运行时编译、JNDI、JMX/日志/agent、音效、PNG 星图、可选联网 | world, sim, config |
| `fc-audit` | API 覆盖审计：源码/classfile 扫描器为主，注解处理器仅作补充标记 | 任一模块源码/产物（编译期 + 构建期） |
| `fc-testkit` | 自研微型测试框架（注解 + 反射 runner） | 零第三方 |
| `fc-runtime` | `main` 装配、启动器、打包（jlink 可选） | 全部 |

- **依赖方向**：`fc-world ← fc-config ← fc-sim / 领域模块 ← fc-cli / fc-runtime`；领域模块之间**不互相依赖**，跨域协作走 `WorldState` + 事件，避免环。
- **sealed 约束**：JPMS 命名模块下，sealed 根与 permitted 子类必须在同一模块（JLS §8.1.6）。因此 `Location`/`Link`/`GameEvent` 及其全部 permitted 类型只能放在 `fc-world`；领域模块只提供行为（策略/结算器），不新增 sealed 子类型。扩展走 `non-sealed` 的 `ExtensionLocation` / `ExtensionEvent`。
- **`WorldState` 与 `GameLoop` 分离**：`WorldState`（纯数据根）在 `fc-world`，`GameLoop`/`TickPipeline` 在 `fc-sim`，从而消除 `World` ↔ `TickPipeline` ↔ `Chronicle` 的循环依赖；`Chronicle` 只留接口在 world，实现放 `fc-history`。
- **划分依据是世界系统与依赖关系，不是 Java API 分类**；禁止为凑覆盖率拆分模块。
- 构建工具边界：Maven 只负责编译/测试/打包调度，运行 = `java -jar fc-runtime.jar`，JDK 21 即全部运行时；`mvn dependency:tree` 应无第三方 compile/runtime 依赖。构建期检查跨模块 sealed 违规与模块依赖方向。

---

## 13. 纯命令行的可玩性〔重点问题 9〕

**信息分层**（避免一屏数字墙）：`概览 → 筛选 → 详情` 三级；`status` 只给 8 行关键面板（日期/资金/燃料/食物/舰队状态/殖民地状态/预警/待决事件），细节用 `fleet 3`、`crew --skill=engineer`、`market --at=K-7` 下钻。

**叙事化回合报告**：按严重度排序的事件以短句叙述（「纪事号在 K-7 遭遇离子风暴，工程组耗时 2 日修复护盾，消耗备件 3」），而非裸露的数值增减表；报告尾部附「因此改变：航线风险↑、备件库存↓、商会态度→戒备」，直接呼应因果链。

**命令围绕游戏动作组织**（任务书 §2.4 硬要求，绝无 API 分类命令）：

| 类别 | 命令示例 |
|---|---|
| 查看 | `status` `map [层]` `scan <地点>` `fleet` `ship <id>` `crew [过滤]` `stock` `market` `factions` `research` `chronicle [...]` `log` |
| 经营 | `build` `upgrade` `assign` `recruit` `refit` `repair` `budget` `policy` `ration` |
| 航行/探索 | `route <from> <to>` `sail` `survey` `recall` `transfer` `supply` |
| 经济/科研 | `trade` `sell` `buy` `research <tech>` `commission` `accept` |
| 外交 | `envoy` `treaty` `gift` `threaten` `declare` |
| 系统 | `next [n]` `save [槽]` `load [槽]` `saves` `config` `lang` `export` `query`(SQL 模式) `macro` `quit` |

**可玩性保障**：`help` / `man <命令>` 与上下文提示；未知命令给编辑距离最近建议；参数错误给具体示例；命令历史（自研行编辑器）；**Tab 补全为尽力而为**——标准 `Console` 不暴露按键级读取，能探测到 TTY 时启用，否则退化为「行尾建议 + `?` 列出候选」；ASCII 星图分层（地形/资源/迷雾/势力/航线）+ 可选 PNG 导出；分页 `more`；ANSI 颜色可关；`macro`/`.replay` 支持脚本化长局；致命错误前自动抢救存档。

---

## 14. 关键技术决策与理由

| # | 决策 | 理由 | 备选与否决 |
|---|---|---|---|
| 1 | **统一世界规则（五抽象 + 一时间轴）** | 任务书硬性要求；从类型系统层面杜绝「两个子游戏拼接」 | 双模拟器：代码重复、数值漂移、历史割裂 |
| 1a | `Ship` 也是 `Location`（移动位置）；`Colony` 是节点、`Facility` 归属殖民地子图 | 船员、货舱、在途状态需要与星域同一套位置/库存/事件规则，否则舰队又是一套模拟器 | 船=纯舰队对象：位置系统与事件因果被拆成两套 |
| 2 | 回合制 + 确定性重放 | 经营策略的标准心智模型；同种子重放是最强回归手段 | 实时模拟：CLI 不友好、难测试 |
| 3 | 虚拟线程承载 `Agent` 决策（JEP 444 正式 API） | 每主体阻塞式日程最自然；百级主体无压力 | **不用** `StructuredTaskScope` / `ScopedValue`（JDK 21 仍为 Preview，任务书禁止）；用 `ExecutorService` + 固定顺序汇合 |
| 4 | sealed + record + 模式匹配 switch | Java 21 正式语言特性；`Location`/`Link`/`GameEvent` 天然代数数据类型 | 访问者模式：样板代码多 |
| 5 | 事件分两层：Tick 内用**有序同步队列**，`Flow` 仅在 tick 结束后向报告/遥测投递 | 结算顺序与重放必须确定；`SubmissionPublisher` 默认异步分发会破坏同种子一致性 | 全程 `Flow`：重放哈希不稳定；全部自研：失去标准背压能力 |
| 6 | `BigDecimal` 全链路经济 | 资金/价格/债务禁止浮点漂移，守恒断言可验证 | double：长局误差累积 |
| 7 | 存档 = 加密 JAR + 对象流 + 校验和 | zip/jar/crypto/nio/`ObjectInputFilter` 全是 SE 标准；白名单过滤是教科书级安全实践 | JSON 手写解析：SE 无标准 JSON API；第三方库违规 |
| 8 | 自研**只读** JDBC 驱动查询编年史 | `java.sql` 是 SE 大模块，「用 SQL 查世界史」本身是好玩法 | 内嵌数据库 = 第三方 |
| 9 | `javax.tools` 运行时编译玩家规则 + 自研 `ScriptEngine` DSL | 「Java 即模组语言」是独有价值；`java.compiler`/`java.scripting` 正式且稳定（JDK 21 无内置脚本引擎，故自研 SPI 实现） | 仅做 DSL：编译器/语言模型模块覆盖不足 |
| 10 | 无头 `java.desktop` 子集（image/geom/font/imageio） | ASCII 之外的 PNG 星图/统计图有真实玩法价值；headless 为 SE 原生支持 | 引入 Swing GUI：违背 CLI 定位，且属明确排除域 |
| 11 | 自定义 `Chronology`（启程历） | `java.time.chrono` 的设计用途即非 ISO 历法；单一时间轴的叙事组成 | 换算表 hack：浪费 API 且易错 |
| 12 | 自研微型测试框架 `fc-testkit` | 任务书禁止第三方依赖，JUnit 不可用；注解+反射 runner 是 SE API 的合理使用 | 手工 main 测试：不可持续 |
| 13 | Maven + 可选 jlink 打包 | 构建工具不属运行时依赖；jlink 展示模块系统 | Gradle 等价；无构建工具不可维护 |
| 14 | 独立 `fc-config` 模块，配置在 M0/M1 即可用 | 世界规模、配方、势力、初始参数从第一天起就是数据驱动；放到 M5 会导致 M1–M4 硬编码后返工 | 配置塞进 `fc-persist`：存档与配置职责混淆，启动期依赖倒置 |
| 15 | 覆盖率审计以**源码/classfile 扫描**为主，注解处理器为辅 | 注解处理器只能看到注解点，无法证明方法体内是否真正使用某类型；静态扫描 + 运行证据才能支撑阶段五 | 仅注解处理器：证据不足、易被「只加注解不调用」绕过 |
| 16 | RNG 流状态入档（算法 + 种子 + 流位置；或种子 + 操作日志重放） | 只存主种子无法复现已消耗的随机流，save/load 后行为漂移 | 只存种子：读档后随机序列错位 |
| 17 | 覆盖率目标按**逐包核算的现实上限**定：包级 ≥80%（上限 82.4%）、类型级 65–72%、模块级 ≥90% | 18 个 Swing 包 / 797 类型不可用；合计约 31 包 / 1089 类型需排除，`(176−31)/176 = 82.4%` 即包级天花板，类型级上限 ≈ `(3836−1089)/3836 = 71.6%`；旧「≥85% / 15–20 包排除」与逐包核算不符 | 沿用 ≥85%：实际达不到，报告失真；定 ≥95%：需塞无意义 Swing 调用，违反红线 |

**显式不使用的 API 类别**：FFM（`java.lang.foreign`）、字符串模板、结构化并发、作用域值——均为 JDK 21 Preview，按任务书 §7 禁止用于正式实现；`jdk.incubator.*` 孵化模块同样排除。阶段一已将这些 38 个类型单独登记、排除分母。

---

## 15. API 覆盖策略（域级，**非逐类型映射**）

> 任务书 §6 阶段二要求「不要提前进行逐个 Java SE API 类型的功能映射」。本节只给域级策略与风险预判；**3874 行逐类型映射表在阶段三**基于冻结清单 `coverage-baseline.csv` 生成。

**冻结口径（阶段一）**：清单边界 3874 类型 / 177 包 / 20 模块；覆盖率分母 **3836 类型 / 176 包 / 20 模块**（扣除 38 个 Preview 类型与整包纯 Preview 的 `java.lang.foreign`）。三类扩展清单（`extension-smartcardio.csv` 13 / `extension-preview.csv` 38 / `extension-protected-nested.csv` 168）不进分母。阶段三标记 `Excluded` **只改变映射状态，不改变分母**——排除项照常计入覆盖率的分母与分子统计口径（分子是否命中另计）。

| 游戏系统 | 预期关联的 API 域（模块→包，域级） | 玩法理由 |
|---|---|---|
| 统一世界 / 时间 | java.base→java.lang, java.util, java.time(.chrono/.temporal), java.util.random | 值对象、集合、启程历、确定性随机 |
| 模拟 / 事件 | java.base→java.util.concurrent(Flow/.locks/.atomic), java.lang(Thread) | 虚拟线程主体、汇合、事件总线 |
| 舰队 / 殖民 / 经济 | java.base→java.math, java.util, java.io, java.nio | BigDecimal 账本、配方、库存、物流 |
| CLI | java.base→java.io(Console), java.text, java.util.regex; java.logging | 行编辑、折行/格式化、命令解析 |
| 存档 / 配置 | java.base→java.io, java.nio.file/.channels, java.util.jar/.zip, java.security, javax.crypto, java.util.prefs, java.util(ResourceBundle/Locale) | 加密 JAR 存档、原子写、偏好、本地化 |
| 编年史 / 查询 | java.sql(.rowset), java.xml(SAX/DOM/StAX/XPath/transform/validation), java.xml.crypto | 自研 JDBC、XML 互导、XSLT 报告、mod 签名 |
| 星图 / 报告 | java.desktop→java.awt.image/geom/font, javax.imageio | 无头 PNG 星图与统计图 |
| 观测 / 扩展 | java.logging, java.management(.rmi/.monitor), java.instrument, java.rmi, java.compiler(javax.tools/lang.model/annotation.processing), java.scripting, java.naming, java.net.http, java.nio.channels(Selector), javax.sound.* | JMX、agent、宏、运行时编译、JNDI、电讯/观战、音效 |
| 构建 / 审计 | java.compiler→javax.annotation.processing, javax.lang.model; java.util.spi(ToolProvider) | 覆盖审计注解处理器、工具注册 |

**排除域预判**（阶段三逐类型给理由）：Swing/AWT GUI 主体、`javax.accessibility`、`java.awt.dnd`、GUI 事件类（CLI 无 GUI）；Preview 38 个（任务书禁止）；`java.smartcardio`（冻结裁决排除分母）；`SecurityManager` forRemoval 体系与 applet（业务无关且将移除）；`org.ietf.jgss` / `javax.security.sasl` 大部（单机游戏无 Kerberos 场景）；`javax.print` / 剪贴板等无头外设（环境限制，阶段三小样实验后定状态）。

**量化预期（逐包核算，诚实预估）**：
- **包级**：约 **31 包 / 1089 类型**需整体排除（其中 18 个 Swing 包 / 797 类型最确定）。剩余 `176 − 31 = 145` 包，包级覆盖**上限 82.4%**，目标定为 **≥ 80%**。
- **类型级**：分母 3836 类型，扣除约 1089 个不可用类型后上限约 **72%**；现实目标区间 **65%–72%**（`java.base` 预计可达 80%+，`java.desktop` 的非 GUI 子包——image/geom/font/imageio——可达 90%+）。若阶段三证明部分被排除包的边缘类型仍可合理使用，上限可上修。
- **模块级**：20 个内容模块全部接入，目标 **≥ 90%**（每个模块至少有一个真实玩法场景）。
- 排除项必须写具体原因码（业务无关 / 环境限制 / 特殊用途 / 其他）；**不因排除而修改分母**，不粉饰统计。
- 上述数字为阶段二预判，阶段三逐类型映射后以实际清单回填与修正。

---

## 16. 测试与验证系统

1. **确定性重放**：同种子 + `.replay` 命令脚本 ⇒ 逐回合状态哈希一致（世界模拟最重要的回归防线）。
2. **统一规则一致性测试**（本设计特有）：同一 `Agent` / `Recipe` / `Transfer` 在星域尺度与殖民地尺度的行为完全一致，防止两套实现漂移。
3. **经济守恒**：库存永不为负、交易/转移前后总量守恒（`BigDecimal` 精确断言）。
4. **并发压测**：60 `Agent` × 1000 回合无死锁、无账本竞态（领域锁规约 + 压测开关）。
5. **存档往返**：保存→加载→状态哈希一致；加密/损坏/版本不符负样本。
6. **CLI 快照**：状态面板与回合报告的黄金文本（按 locale 分目录）。
7. **统计验证**：事件抽样分布 χ² 检验（随机引擎正确性）。
8. **构建验证**：`mvn dependency:tree` 无第三方 compile/runtime 依赖；构建期模块/包检查确认无违规模块与跨模块 sealed 子类；`fc-audit` 源码/classfile 扫描结果与阶段一冻结清单比对。
9. **开局可达成性（D0）**：固定种子 + 初始存档，30 回合内建立燃料/食物正循环，防止「燃料约 20 回合但 D0 要求 30 回合」的死局。
10. **存档 RNG 一致性**：save → load → N tick 的状态哈希一致；RNG 流状态入档或按种子 + 操作日志重放。
11. **冲突结算重放**：`threaten` / 劫掠 / 势力冲突的货损、耐久、关系变化可重放，且不产生负库存。

---

## 17. 预计实现阶段（阶段四分批计划）

| 批次 | 游戏内容 | 里程碑验收 |
|---|---|---|
| M0 骨架 | 15 模块骨架（含 `fc-config`）、sealed 模型的模块内聚校验、`Stardate`、CLI 壳、`next` 空转、`fc-testkit`、配置装载与校验骨架 | `next 30` 跑通并打印启程历；`mvn dependency:tree` 无第三方依赖；配置坏值能报错 |
| M1 星域与舰队 | 配置驱动的 16 地点 `LocationGraph`、航线、3 艘船、燃料/资源账本、ASCII 星图、最小 Tick（时间/移动/库存三阶段） | 完成一次往返航行并安全返航；世界数据全部来自配置而非硬编码 |
| M2 统一主体与事件 | 船员/居民/势力 AI、完整 12 阶段 Tick、确定性事件队列、`Flow` 后置报告、编年史、待决决策 | 核心循环可玩；「离子风暴」穿透 8 系统链路可追溯；同种子重放哈希一致 |
| M3 经济与殖民 | 前哨站→殖民地、配方生产、市场贸易、财务、运输物流、补给线 | D1 达成（殖民地 1 级 + 首条贸易线）；`Transfer` 守恒断言通过 |
| M4 科研 / 外交 / 探索 | 技术树与 `TechEffect`、势力关系/委托、遗迹发现、冲突/威胁结算、成就与双终局 | 「高效曲率线圈」因果链（§7）完整生效；冲突重放无负库存 |
| M5 持久化与外围 | 加密存档（含 RNG 流状态）、本地化/存档迁移/mod 配置合并、编年史 SQL/XML/XSLT、宏/运行时编译/mod SPI/JNDI、JMX/agent/音效/PNG 星图、可选联网（可降级） | save/load 往返 + N tick 状态哈希一致；`query` 模式可用；全部降级路径验证 |
| M6 审计与打磨 | `fc-audit` 源码/classfile 扫描接入、覆盖数据回填、平衡性、性能、文档、构建运行说明 | 阶段五审计报告 + 最终交付；扫描证据与冻结清单逐行可对 |

每批结束：更新映射表状态（阶段三产出）、运行测试、检查是否引入无意义 API 使用（任务书 §5 红线）、游戏始终保持可构建可运行。

---

## 18. 与 API 覆盖目标相关的风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| `java.desktop` GUI 主体不可用 | 包级上限 82.4%、类型级上限 ≈ 72%（§15） | 逐包核算并如实记录 `Excluded` + 理由；不粉饰统计、不改分母 |
| Preview 特性全部禁用 | 虚拟线程之外的现代并发/FFM 覆盖缺失 | 用正式 API 等价实现；38 个类型单独登记 |
| 无头/外设差异（剪贴板、打印、音频、智能卡） | 个别类型环境依赖 | 能力探测 + 优雅降级；`Excluded` 原因码 = 环境限制 |
| 自研 JDBC / ScriptEngine / JNDI 工作量被低估 | M5 延期 | 精简为只读/最小语义；砍范围不砍合理性 |
| 虚拟线程 + 共享账本并发 | 死锁/竞态/确定性破坏 | 固定顺序汇合、领域锁规约、压测入 CI、有序集合 |
| 覆盖率诱惑（塞无意义调用） | 违反任务书 §5 | 阶段五静态审计 + 人工复核；宁可低分不造假 |
| 统一世界规则被后续扩展破坏 | 退化为多个子游戏 | 一致性测试（§16-2）+ 代码评审红线 |
| 世界规模无节制膨胀 | 性能与可玩性下降 | §3 规模红线 + 配置驱动 + 惰性生成 |
| **sealed 与 JPMS 模块冲突** | 编译期直接失败，或被迫拆散模型 | 全部 sealed 根与 permitted 类型同放 `fc-world`；构建期校验模块边界；扩展只用 `non-sealed` |
| 配置装载排到 M5 才做 | M1–M4 硬编码世界数据，后期返工 | `fc-config` 随 M0 落地，M1 起数据驱动；配置校验失败即启动报错 |
| `Flow` 异步分发破坏确定性 | 同种子重放哈希漂移 | Tick 内同步有序队列；`Flow` 只在 tick 结束后投递叙事/遥测 |
| RNG 状态未入档 | 读档后随机序列错位、重放失效 | 存档写入算法/种子/流位置；§16-10 往返测试兜底 |
| `Agent` 类职责膨胀 | 船员/居民/使者语义混杂，难测试 | 角色用 `AgentRole` 表达，行为拆策略；`WorldView` 只读隔离 |
| 审计证据不足（只靠注解） | 阶段五无法证明真实使用 | 源码/classfile 扫描为主 + 运行场景证据；§14-15 |
| CLI Tab 补全不可移植 | 标准 `Console` 下无效，体验落空 | 能力探测 + 行尾建议/候选列表降级；不阻塞主流程 |

---

## 19. 待用户确认的决策点

1. **双终局路线**（归途 / 扎根）与 D0–D4 发展阶段是否符合预期？
2. **初始规模参数**（1 星域 / 16 地点 / 4 势力 / 3→8 船 / 36 船员 / 1 殖民地 / 12 资源）是否认可？
3. **统一世界规则**的五抽象 + 单一时间轴粒度是否合适（这是本设计的核心架构判断）？
4. **`Ship` 作为移动 `Location`**、`Colony` 为节点 / `Facility` 归属殖民地子图——是否认可这一位置模型？
5. **sealed 模型全部收敛在 `fc-world`**（JPMS 同模块约束），领域模块只提供行为、扩展走 `non-sealed` 接口——是否认可？
6. 可选联网/音频/远程观战特性是否保留（默认保留、全部可降级）？
7. 模块前缀 `fc-`、**15 模块划分**（新增 `fc-config`）、Maven + 可选 jlink 构建是否认可？
8. **覆盖率预期**：包级 ≥80%（上限 82.4%）、类型级 65–72%、模块级 ≥90%，排除项不改分母——是否接受？
9. **覆盖率分母最终口径**：沿用任务书 §3.3 派生的 **3836 类型 / 176 包 / 20 模块**（而非清单边界 3874 / 177）——是否确认？

> 阶段一已冻结的口径（smartcardio / protected 嵌套 / Deprecated / Preview）不再列为待确认项。

**以上确认后进入阶段三：逐类型 API-功能映射表**（基于 `docs/phase1/data/coverage-baseline.csv`，为 3874 行建立 Planned / Implemented / Verified / Excluded / Pending 状态与证据字段；其中分母为 3836 行，38 行 `EXCLUDED_PREVIEW` 固定为 `Excluded`）。

---

## 20. v3 审查修订摘要

本次修订针对复审发现的架构、排期与统计问题，逐项落实：

**P0（架构硬伤）**
- **sealed 与模块冲突**：明确 JLS §8.1.6 约束，全部 sealed 根与 permitted 类型收敛到 `fc-world`；新增 `non-sealed` 的 `ExtensionLocation` / `ExtensionEvent` 作为 mod 扩展口；删除「领域模块各自扩展 sealed 子类」的错误表述。
- **依赖环**：拆出纯数据根 `WorldState`（`fc-world`）与 `GameLoop`/`TickPipeline`（`fc-sim`）；`Chronicle` 仅接口在 world，实现移入 `fc-history`；新增 `fc-config` 切断「配置由 `fc-persist` 在 M5 提供」的倒置依赖。
- **`Ship` 缺失**：`Ship` 纳入 `Location` 层级（移动位置，含 `parent()` / `cargo()` / `voyage()`）；`Colony` 为图节点，`Facility` 为殖民地子图。

**P1（正确性与可验证性）**
- 配置装载提前到 M0/M1，M5 只保留本地化、存档迁移与 mod 配置合并。
- 覆盖率预期改为逐包核算：31 包 / 1089 类型排除，包级上限 82.4%、类型级上限 ≈72%；明确 `Excluded` 不改分母。
- 审计从「注解处理器为主」改为「源码/classfile 扫描为主、注解为辅」。
- 事件分两层：Tick 内确定性同步队列，`Flow` 仅 tick 后投递；RNG 流状态写入存档。
- CLI Tab 补全降级为尽力而为（标准 `Console` 不暴露按键读取）。
- 补充最小的冲突/威胁结算与重放测试；修正资源口径为 11 种货物 + credits（credits 只记 `ResourceLedger`）。
- 冻结清单 SHA-256 的行尾问题：新增 `.gitattributes`（`eol=lf`），并在冻结说明中注明哈希基于 LF 内容。

**P2（术语与细节）**
- `Module` → `ShipModule`、`ResourceBundle` → `ResourceLot`（避免与 `java.util.*` 同名混淆）。
- `Crew`/`Resident`/`Envoy` 统一为 `Agent` 的角色（`AgentRole`），不再是并列类。
- 「四类扩展清单」更正为三类（smartcardio / preview / protected-nested）。
- `WorkUnit` 支持多 `Agent` 协作。

---

## 附：任务书要求对照索引

| 任务书阶段二要求 | 本文档位置 |
|---|---|
| 16 个系统 | §4.1–§4.16 |
| 重点问题 1 长期目标 | §1.4 |
| 重点问题 2 核心决策 | §1.5 |
| 重点问题 3 系统相互影响 | §7 |
| 重点问题 4 自主行动 | §9 |
| 重点问题 5 时间演化 | §8 |
| 重点问题 6 玩家改变世界 | §10 |
| 重点问题 7 避免数值拼接（统一规则） | §2、§7 |
| 重点问题 8 规模控制与扩展 | §3 |
| 重点问题 9 CLI 可玩性 | §13 |
| 完整玩法说明 / 核心循环 | §1、§5 |
| 世界观与初始背景 | §1.2 |
| 游戏目标与发展阶段 | §1.4 |
| 系统关系图 | §6 |
| 核心数据模型 | §11 |
| 模块划分 | §12 |
| 时间推进与模拟结算机制 | §8 |
| 关键技术决策 | §14 |
| 初始世界规模 | §3.1 |
| 预计实现阶段 | §17 |
| 测试与验证系统 | §4.16、§16 |
