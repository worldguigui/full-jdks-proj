# M0 骨架

## 目标

建立可编译、可运行、可测试的工程骨架，固定世界模型、时间、模块边界与验证方式，为后续里程碑提供不可动摇的基础。

## 交付内容

### 1. 模块骨架

15 个 Maven 模块按依赖方向组织（见 `docs/architecture.md` §4）。底层模块先稳定，上层模块可以暂时为空：

- `fc-world`：sealed 世界模型、账本、`WorldState`、`Stardate`、编年史接口；
- `fc-config`：配置模型与 XML/XSD 装载；
- `fc-sim`：游戏循环、Tick 管线、确定性随机、事件队列、状态哈希；
- 领域模块：`fc-fleet`、`fc-colony`、`fc-economy`、`fc-science`、`fc-diplomacy`、`fc-history`；
- 外围模块：`fc-cli`、`fc-persist`、`fc-extensions`、`fc-audit`、`fc-testkit`、`fc-runtime`。

领域模块之间不得互相依赖；`fc-testkit` 只以测试作用域出现。

### 2. 世界模型约束

- `Location`、`Link`、`GameEvent` 为 sealed 根，全部 permitted 类型与根同处 `fc-world`。
- `Ship` 是 `Location` 的子类型，既进入地点图，又拥有货舱与在途状态。
- 船员、居民、使者统一为 `Agent` 的三种角色，不建立三个并列类。
- 货物只通过 `ResourceLedger` 修改；信用点只通过 `MoneyLedger` 修改。

### 3. 时间与空转

`Stardate` 以天为单位，只能单调前进。即使没有世界系统，引擎也能连续推进 N 天并返回新的星历；时间推进不依赖系统时钟。

### 4. 测试基础

`fc-testkit` 提供 `@FcTest` 注解与反射 runner。测试主类在失败时退出码为 1；Maven `test` 阶段必须实际执行测试，而不是只编译。

## 验收标准

| 项 | 标准 | 验证 |
|---|---|---|
| 模块可编译 | `mvn -B test` 成功，所有模块参与反应堆 | 构建输出 |
| 零第三方依赖 | 编译与运行依赖只有 `fc-*` 模块 | `mvn dependency:tree` |
| 世界模型 | 五种抽象存在；`Location`/`Link`/`GameEvent` 的 permitted 类型都在 `fc-world` | `WorldSmokeTest#sealedLocationHierarchy`、`WorldSmokeTest#eventPatternMatch` |
| 时间 | 一天一个 tick，星历单调前进 | `WorldSmokeTest#stardateAdvances`、`M1TickTest#timeAdvancesOneDayPerTick` |
| 货物账本 | 库存不下溢；转移守恒；信用点不能进入货物库存 | `WorldSmokeTest#stockArithmetic`、`WorldSmokeTest#stockUnderflowThrows`、`WorldSmokeTest#creditsCannotEnterPhysicalStock`、`WorldSmokeTest#ledgerTransferConservesTotals` |
| 财务账本 | 账户转账守恒；透支被拒绝；利息计入欠款 | `WorldSmokeTest#moneyTransferConservesAndRejectsOverdraft`、`WorldSmokeTest#loanInterestAccruesDebt` |
| 配置错误 | 无效 XML/XSD/语义错误启动即失败 | `ConfigTest` 结构校验用例 |
| 可运行 | CLI 可启动、帮助与退出可用 | `RuntimeSmokeTest#coreCommandsRespond` |
