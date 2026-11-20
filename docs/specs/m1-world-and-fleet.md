# M1 星域与舰队

## 目标

由配置装配出可航行的星域：地点、航线、舰船、主体与初始库存全部来自世界文件；玩家可以查看星图、启航、消耗燃料并在有限天数后抵达。

## 世界规模

默认世界（`world/default-world.xml`）的验收规模：

| 项 | 值 |
|---|---|
| 静态地点 | 16（恒星系、行星、轨道站、异常点、殖民地、设施） |
| 航线 | 17（无向边，按 `travelTicks` 计算行程） |
| 舰船 | 3 |
| 主体 | 36 |
| 势力 | 4 |
| 资源价格定义 | 11 |
| 配方 | 8 |
| 玩家据点 | 1 个 0 级前哨站，3 档升级 |
| 初始玩家信用点 | 1000 |
| 世界种子 | 20261120 |

这些数值是默认世界的内容，不是核心代码中的常量；更换世界文件即可改变规模。

## 配置装载

输入为 XML，XSD 校验后再做语义校验。以下情况必须导致启动失败，并给出可定位的错误信息：

- XML 不合法、XSD 校验失败、含 DOCTYPE；
- 地点 id 重复、父地点不存在；
- 航线端点不存在；
- 舰船 id 与地点 id 冲突；
- 未知主体角色、未知势力、未知所有者语法、未知势力优先货物；
- 航线风险不是有限数；
- 初始信用点为负；
- 在货物库存中写 `credits`；
- 配方没有产出、关系指向未知势力。

默认世界必须恰好有一个 `owner="player"` 的 0 级前哨站，且带三档升级配置。

## 地点、航线与寻路

- 静态地点与舰船都是 `Location`，全部进入同一 `LocationGraph`；舰船 id 在内部映射为独立的 `LocationId`，与地点 id 不冲突。
- 父子层级（行星属于恒星系、设施属于殖民地等）由配置 `parent` 建立，父节点必须存在。
- 航线按无向边参与寻路；最短航路按总天数最小计算，并列时取总成本更小者。
- 航行消耗燃料：所需燃料等于整条航路各边 `cost` 之和；舰船货舱燃料不足时启航被拒绝。

## 航行生命周期

1. `sail <舰船> <目标>`：校验舰船未在航、目标存在、航路可达、燃料足够；成功后扣除燃料，写入`FuelConsumed`与`ShipDeparted`，船进入在航状态。
2. 每个 tick 的阶段 7 推进一天航程；到达 `totalTicks` 时更新停泊地点、清除在航状态，并产生 `ShipArrived`。
3. 在航舰船不能再次启航，也不能交易；抵达后才能继续操作。

主体迁徙遵循同一原则：沿航线计算行程，逐日推进，抵达后才改变所在地点，不存在瞬间传送。

## 库存与自然产出

- 地点与舰船货舱在装配时开立权威账本分区，初始库存来自配置。
- 地点可通过配置获得每日自然产出；环境恶劣或生产中断时产出停止或减半（见 M2 的环境阶段）。
- 任何库存变化都同步地点视图，`verifyLedger` 可以核对视图与权威账本一致。

## 验收标准

| 项 | 标准 | 验证 |
|---|---|---|
| 配置规模 | 16 地点 / 17 航线 / 3 舰船 / 36 主体 / 4 势力 / 11 资源 / 8 配方 | `ConfigTest#defaultWorldMatchesSpecifiedScale` |
| 玩家据点 | 恰好一个玩家 0 级前哨站，三档升级 | `ConfigTest#defaultWorldHasSinglePlayerOutpost` |
| 配置校验 | 上述错误全部拒绝 | `ConfigTest` 全部结构校验用例 |
| 装配 | 配置变为图、账本、主体并保留父层级 | `M1TickTest#assemblesFromDefinition`、`M1TickTest#ledgerSeededFromStocks`、`M1TickTest#parentLocationsAreIndexed` |
| 寻路 | 多跳最短、双向对称 | `VoyageTest#shortestPathIsMultiHop`、`VoyageTest#shortestPathIsSymmetric` |
| 启航 | 燃料经账本扣除；燃料不足、在航、同地、不可达均拒绝 | `VoyageTest` 启航用例 |
| 抵达 | 逐日推进，到时抵达 | `M1TickTest#shipVoyageAdvancesAndArrives` |
| 账本一致 | 时间推进后库存与账本一致 | `M1TickTest#inventoryAccruesProductionAndLedgerStaysConsistent` |

## 当前未覆盖

- 货舱容量上限未实现，交易与装载不受容量限制。
- 航线 `risk` 与风暴风险修正已进入状态，但尚未参与航程事故结算。
