# 阶段五 M2 实现报告（主体与事件）

> M2 目标：船员/居民/使者自主决策循环、完整十二阶段 Tick 管线、确定性随机流（同种子可重放）、事件分发（tick 内有序队列 + tick 后 Flow 广播）。

---

## 一、验收结果

| 验收项 | 结果 | 证据 |
|---|---|---|
| **十二阶段管线** | ✅ | 启动即打印 `1-time → 2-environment → 3-faction → 4-perceive → 5-decide → 6-work → 7-voyage → 8-consumption → 9-market → 10-event → 11-chronicle → 12-report` |
| **主体自主决策** | ✅ | 14 个主体按饥饿/疲劳/士气自行决定进食、休息、工作、迁徙；`crew` 命令可见实时状态 |
| **世界不依赖玩家演化** | ✅ | 只输入 `next 150`，世界自行发生：饥饿→觅食迁徙（天狼/织女/天津四 → 有粮地点）、进食、离子风暴 8 次 |
| **确定性（可重放）** | ✅ | 同种子跑两次，`log all` 输出逐字节一致；单测 `sameSeedSameHistory` 覆盖 |
| **事件分发** | ✅ | tick 内事件走有序队列，由阶段 11 落库；tick 后经 `Flow`（`SubmissionPublisher`）广播给订阅者 |
| **单元测试** | ✅ | fc-sim M2 11 个 + M1 7 个、fc-fleet 6 个，共 24 个通过 |

---

## 二、十二阶段管线（M2 落地情况）

| # | 阶段 | 实现状态 |
|---|---|---|
| 1 | `1-time` 时间推进 | ✅ Stardate +1，采样环境强度（`RngStreams.WEATHER`） |
| 2 | `2-environment` 环境与生态 | ✅ 每日产出入库存与账本；天气 ≥85 时产出减半 |
| 3 | `3-faction` 势力 AI | ⏳ **占位**（M4：需关系矩阵与势力资源） |
| 4 | `4-perceive` 主体感知 | ✅ 按性格与指派刷新饥饿/疲劳/士气 |
| 5 | `5-decide` 主体决策 | ✅ 生存＞休息＞士气（可拒绝指令）＞工作；饥饿无粮时觅食迁徙 |
| 6 | `6-work` 生产与工作 | ✅ WorkUnit 推进；**虚拟线程并发 + 按 key 固定顺序汇合** |
| 7 | `7-voyage` 航行与物流 | ✅ 在航推进、抵达更新位置、写事件 |
| 8 | `8-consumption` 消耗与维护 | ✅ 日常口粮消耗（饥饿 ≥55 进食，缓解 25）；缺粮则士气受损 |
| 9 | `9-market` 市场与财务 | ⏳ **占位**（M3：供需定价与经济结算） |
| 10 | `10-event` 事件引擎 | ✅ 环境强度加权的离子风暴抽样（地点按 id 有序抽取） |
| 11 | `11-chronicle` 编年史落库 | ✅ 唯一的状态写入点（有序 drain → record） |
| 12 | `12-report` 报告聚合 | ✅ 只读摘要：环境强度、在岗/休息/饥荒人数 |

> 阶段 3 与 9 是**显式占位**：它们的位置在 M2 就固定下来，M3/M4 只需填充内容而不改动顺序——这是保证"十二阶段从 M2 起就稳定"的关键。

---

## 三、统一主体（Agent）

**同一类型，三种角色**（`CREW` / `RESIDENT` / `ENVOY`），符合"不得定义 Crew/Resident/Envoy 子类"的约束。

| 组成 | 说明 |
|---|---|
| `Needs` | 饥饿、疲劳、士气（0..100），不可变值对象 |
| `Trait` | DILIGENT / LAZY / STOIC / ANXIOUS，影响需求增速与拒绝概率 |
| `Assignment` | IDLE / WORKING / RESTING / TRAVELING |
| 决策循环 | perceive →（rankNeeds + decide）→ act → record，落在阶段 4/5/8/11 |

**实现出的行为**（全部由状态驱动，非脚本硬编码）：

- 疲劳累积到 70 → 自行休息；休息恢复后自动复工；
- 饥饿到 55 → 日常进食（消耗 1 单位口粮，扣账本）；
- 饥饿到 75 且本地无粮 → **就近觅食迁徙**（BFS ≤2 跳，按地点 id 有序）；
- 士气 ≤20 → 按性格概率**拒绝指令**（ANXIOUS 45% / LAZY 35% / STOIC 15% / DILIGENT 10%）；
- 长期工作有小概率技能成长；
- 饥饿告警去重（同一状态只报一次），避免日志刷屏。

---

## 四、确定性随机流

`fc.sim.rng.RngStreams`：

- 算法 `L64X128MixRandom`（`RandomGeneratorFactory`，可跳跃，跨 JVM 稳定）；
- 主种子 + 子系统名经 SplitMix64 派生独立流：`weather` / `events` / `agents` / `market` / `factions`；
- 互不干扰：取事件流不会改变主体流序列（单测 `rngSubsystemsAreIndependent` 验证）；
- 可重放：只需保存算法名 + 主种子（`world.xml` 的 `seed` 属性，默认 20261120）。

实测：同种子连续两次 `next 60`，编年史逐字节一致。

---

## 五、事件分发

| 环节 | 实现 | 约束 |
|---|---|---|
| tick 内 | `EventQueue`（有序队列），阶段 5/7/8/10 入队 | 顺序 = 阶段顺序，保证确定性 |
| 落库 | 阶段 11 `drain()` → `Chronicle.record()` | **唯一状态写入点** |
| tick 后 | `EventBus`（`java.util.concurrent.Flow` + `SubmissionPublisher`） | 只做通知，不写状态 |

新增事件类型：`CrewAte`、`CrewMigrated`、`CrewRefusedDuty`、`CrewStarving`。

---

## 六、运行示例（不操作也会演化）

只输入 `next 150`：

```
[SD 19] 进食 郑雯#3（饥饿 77）于 Sirius 天狼
[SD 27] 离子风暴 强度 5 于 Sirius 天狼
[SD 28] 迁徙 何川#4 Sirius 天狼 → Rigel Forge 熔炉殖民地（觅食）
[SD 28] 迁徙 苏禾#5 Rigel 参宿七 → Rigel Forge 熔炉殖民地（觅食）
[SD 48] 离子风暴 强度 2 于 Kepler Belt 开普勒带
[SD 55] 迁徙 檀札#22 Deneb 天津四 → Sol 索尔（觅食）
[SD 64] 离子风暴 强度 3 于 Deneb 天津四
[SD 139] 离子风暴 强度 3 于 Vega 织女
```

150 天后 `crew`：14 人全部存活，士气回升至 60–100，部分人已在新的地点定居。

---

## 七、M2 未覆盖（排期明确）

- 势力 AI（阶段 3）、市场与财务（阶段 9）——M3/M4；
- 战斗/冲突、外交关系矩阵——M4；
- 工作单元目前由测试注入，M3 起由生产配方生成；
- 存档（含 RNG 状态）——M5。

---

## 八、下一步（M3 经济与贸易）

M3 目标：供需 → 价格 → 交易结算（阶段 9 填充）、配方驱动的生产（阶段 6 接入 WorkUnit）、口粮/燃料/能源消耗与维护成本、玩家 `trade` 命令（低买高卖才有博弈）。

前置：价格模型 `PriceModel`、配方表进配置、`fc-economy` 模块开始承载结算逻辑。
