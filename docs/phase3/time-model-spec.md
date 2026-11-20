# 时间模型规范：现实时间与游戏时间分离

> 决策依据：用户裁决「JMX 时间模型 = 现实时间与游戏时间分离，形成正式规范」。
> 本文档是本项目时间语义的**唯一权威定义**，所有涉及时间/时钟/定时/监控的代码必须遵守。

---

## 1. 两种时间，两个时钟源

本项目存在**两种互不相干的时间**，各自有独立时钟源，**绝不混用**：

| 时间 | 时钟源 | 用途 | 是否入档 | 是否参与重放 |
|---|---|---|---|---|
| **游戏时间（Stardate）** | `java.time` 的模拟时钟（可注入 `Clock`） | 世界状态、tick、编年史、经济结算 | 是 | 是（确定性） |
| **现实时间（Wall Clock）** | `System.nanoTime()` / `System.currentTimeMillis()` | 运行监控、采样间隔、心跳、日志时间戳、看门狗 | 否 | 否 |

## 2. 游戏时间（Stardate）

- **唯一来源**：`fc-sim` 的 `SimClock`，封装可注入的 `java.time.Clock`。
- **确定性**：固定世界种子下，`> next N` 两次运行结果逐字节一致——因此 Stardate 只能由 tick 管线推进，**禁止**任何地方直接读 `System.currentTimeMillis()` 来推进游戏时间。
- **入档**：Stardate 是存档的一部分，序列化/反序列化必须可逆。
- **历法**：自研「启程历」`Chronology`（`java.time.chrono`），与 ISO 历法互转。

## 3. 现实时间（Wall Clock）

- **来源**：`System.nanoTime()`（单调递增，用于测量间隔）+ `System.currentTimeMillis()`（墙钟，用于日志/心跳时间戳）。
- **用途**（全部与游戏状态无关）：
  - `> diag` 的 tick 延迟、采样间隔、资源指标采集；
  - JMX `Monitor`/`Timer` 的阈值轮询与周期心跳；
  - 日志环形缓冲的时间戳；
  - 交互超时看门狗（`java.util.Timer`/`TimerTask`）。
- **不入档、不重放**：现实时间读数不影响任何游戏状态；重放时现实时间无关紧要。

## 4. 边界规则（硬约束）

1. **游戏状态不得依赖现实时间**：任何进入 `WorldState`/`Chronicle`/账本的数据，其时间字段必须来自 Stardate，不得来自 `System.*`。
2. **监控不得写入游戏状态**：JMX monitor/timer、`diag` 采样的结果只读、只展示、只落监控日志，不得反哺世界结算。
3. **`java.util.Timer` 只用于交互超时**，不参与世界结算（已在 type-keep 中约束）。
4. **JMX `Monitor` 基类的采样间隔**用 `System.nanoTime()` 测量，`Timer` 用现实时间触发；它们**与 Stardate 完全分离**，即便游戏暂停（tick 不推进），监控仍按现实时间运行。

## 5. 类型归属

| 类型 | 时间语义 | 归属 feature |
|---|---|---|
| `java.time.Clock` / `Instant` / `Duration` / `LocalDate` | 游戏时间（可注入时钟） | CORE-WORLD / CORE-SIM |
| `java.time.chrono.*` / `temporal.*` / `zone.*` | 游戏历法与周期 | CORE-WORLD / CORE-SIM |
| `javax.management.monitor.*` / `timer.*` | 现实时间监控 | OBS-JMX |
| `java.util.Timer` / `TimerTask` | 交互超时看门狗（现实时间） | CORE-SIM（已约束） |
| `System.nanoTime()` / `currentTimeMillis()` | 现实时间测量 | OBS-JMX / LOG-RING |

## 6. 验收

- 固定种子下 `> next 100` 两次结果逐字节一致（证明游戏时间确定性）；
- `> diag` 的采样间隔用现实时间，游戏暂停时 `diag` 仍能采样（证明时间分离）；
- 存档反序列化后 Stardate 一致（证明游戏时间入档可逆）。
