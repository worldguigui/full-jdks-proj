# 阶段五 M0 骨架实现报告

> 阶段五第一个里程碑：M0 骨架。
> 验收标准（阶段二设计 §17）：`next 30` 跑通并打印启程历；`mvn dependency:tree` 无第三方依赖；配置坏值能报错。

---

## 一、验收结果（三项全部通过）

| 验收项 | 结果 | 证据 |
|---|---|---|
| `next 30` 跑通并打印启程历 | ✅ | 输出「时间推进 30 天，当前 SD 30（2200-01-31）」 |
| `mvn dependency:tree` 无第三方依赖 | ✅ | 依赖树除 fc 内部模块外零第三方 compile/runtime/test 依赖 |
| 配置坏值能报错 | ✅ | `ConfigException: 配置错误 [world.stardate.epoch]: 非法值: "not-a-number"` |

> 注：M0 时期的临时验收脚本已按用户要求删除（M1 起以「直接开玩」为准）。

---

## 二、交付物

### 2.1 Maven 多模块骨架（15 模块）

- 父 POM `pom.xml`（聚合 + 依赖管理，JDK 21 release）。
- 15 个 `fc-` 模块：world/config/sim/fleet/colony/economy/science/diplomacy/history/cli/persist/extensions/audit/testkit/runtime。
- 依赖方向严格按阶段二 §12：`fc-world ← fc-config ← fc-sim / 领域模块 ← fc-cli / fc-runtime`；领域模块之间不互相依赖。

### 2.2 fc-world 核心 sealed 模型

实现统一世界规则五大抽象 + 值对象：

| 类型 | 说明 |
|---|---|
| `Location`（sealed） | 8 个 permitted：StarSystem/Planet/OrbitalStation/Anomaly/Colony/Facility/Ship/ExtensionLocation，全部收敛 fc-world |
| `Link`（sealed） | WarpRoute/SurfaceRoute/InternalLane |
| `Agent` | 统一行为主体（CREW/RESIDENT/ENVOY 角色） |
| `WorkUnit` | 生产/维修/科研/施工共用 |
| `GameEvent`（sealed） | 8 个子接口 + ExtensionEvent（non-sealed 扩展口） |
| `Stardate` | 单一时间轴（record，可序列化可比较） |
| `Stock` / `ResourceLedger` | 账本分区视图 + 权威守恒账本 |
| `LocationGraph` / `WorldState` / `Chronicle` | 世界状态根 + 编年史接口 |
| `Ids` | LocationId/AgentId/ShipId/FactionId 强类型标识 |

**sealed 约束落实**：sealed 根与全部 permitted 类型在同一模块 fc-world（JPMS 同模块约束，JLS §8.1.6）；扩展走 `non-sealed` 的 `ExtensionLocation`/`ExtensionEvent`。

### 2.3 fc-testkit 自研测试框架

- `@FcTest` 注解 + `TestRunner` 反射 runner（零第三方依赖）。
- 6 个冒烟测试全部通过（Stardate 推进、账本守恒、扣减不足抛异常、sealed 层次、事件模式匹配）。

### 2.4 fc-config 配置装载骨架

- `Config` 接口 + `PropertiesConfig` 实现（强类型读取 int/BigDecimal/bool）。
- `ConfigException` 带键名定位，坏值报错。
- 支持文件路径 + 类路径资源双加载。

### 2.5 fc-sim / fc-history / fc-cli / fc-runtime

- `GameLoop`（`next` 空转，只推进 Stardate）。
- `InMemoryChronicle`（编年史内存实现）。
- `Cli`（命令壳：next/status/help/quit）+ `TokenScanner`。
- `Main`（装配根 + REPL，UTF-8 输出）。

---

## 三、技术要点

1. **UTF-8 输出修复**：Windows 下 `System.out` 默认 GBK，显式设置 UTF-8 输出流，消除中文乱码。
2. **配置双加载**：`--config` 先按文件路径、再按类路径资源加载，坏配置真正报错。
3. **账本守恒在类型层表达**：`Stock`/`ResourceLedger` 的扣减不足抛异常，为 M2 的守恒结算打基础。

---

## 四、M0 范围说明（未实现，属 M1-M4）

M0 是骨架，以下按阶段二 §17 排到后续批次：

- 16 地点 LocationGraph（M1，配置驱动）
- Tick 管线十二阶段（M2）
- 舰队航行/殖民地生产/经济结算（M3-M4）
- 加密存档/本地化（M5）
- 联机聊天室/桌面客户端（M5-M6）

---

## 五、下一步（M1 星域与舰队）

M1 验收（阶段二 §17）：配置驱动的 16 地点 LocationGraph、航线、3 艘船、燃料/资源账本、ASCII 星图、最小 Tick（时间/移动/库存三阶段）；完成一次往返航行并安全返航；世界数据全部来自配置。

M1 前置：`fc-config` 的 XML/XSD 装载（当前只有 Properties），世界数据模型（16 地点配方）从配置生成。
