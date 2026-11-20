# 阶段五 M1 实现报告（星域与舰队）

> M1 验收标准（阶段二 §17）：配置驱动的 16 地点 LocationGraph、航线、3 艘船、燃料/资源账本、ASCII 星图、最小 Tick（时间/移动/库存三阶段）；完成一次往返航行并安全返航；世界数据全部来自配置。

---

## 一、验收结果（四项全过）

| 验收项 | 结果 | 证据 |
|---|---|---|
| 世界数据全部来自配置 | ✅ | 默认 world.xml → 16 地点/16 航线/3 船；`--world` 换成 3 地点配置后世界随之改变 |
| 完成一次往返航行并安全返航 | ✅ | `sail 1 4` → `next 4` → `sail 1 1` → `next 4`；燃料 120→100；最终停泊 Sol |
| Tick 三阶段生效 | ✅ | 阶段顺序 `time → movement → inventory`；10 天后 Deneb Farm food 40→70（+3/天） |
| 单元测试 | ✅ | fc-sim 7 个 + fc-fleet 6 个，共 13 个全通过 |

**说明**：按用户要求，M1 起不再提供验收脚本。直接运行 `java -jar fc-runtime/target/frontier-chronicle.jar` 即可开玩。

---

## 二、新增交付物

### 2.1 fc-config：XML/XSD 世界装载

| 文件 | 作用 |
|---|---|
| `WorldDefinition` | 世界定义数据模型（地点/航线/舰船/产出），纯数据不含 fc-world 依赖 |
| `XmlWorldLoader` | DOM 解析 + XSD 校验 + 语义校验（id 唯一、端点存在、数值非负、kind 合法性） |
| `resources/schema/world.xsd` | 结构约束（kind 枚举、必填属性、数值类型） |
| `resources/world/default-world.xml` | 16 地点 / 16 航线 / 3 舰船 |

安全：禁用 DOCTYPE 与外部实体（XXE 防护）。

### 2.2 fc-sim：世界装配 + Tick 三阶段

| 文件 | 作用 |
|---|---|
| `WorldAssembler` | 把 `WorldDefinition` 装配为 `LocationGraph` + 账本 + 产出表 + 父子层级 |
| `tick/TickStage` | 阶段接口 + 上下文（产出表） |
| `tick/TimeStage` | 时间阶段：Stardate +1 |
| `tick/MovementStage` | 移动阶段：推进在航、抵达更新位置、写编年史 |
| `tick/InventoryStage` | 库存阶段：每日产出计入地点库存与权威账本 |
| `tick/TickPipeline` | 固定顺序执行（M1 三阶段） |
| `GameLoop` | 改为逐日执行管线（保留 M0 无管线兼容） |

### 2.3 fc-fleet：导航与航行

| 文件 | 作用 |
|---|---|
| `Navigation` | Dijkstra 最短路（按 tick 加权，M1 航线按无向边），返回路径/总天数/总油耗 |
| `VoyageService` | 启航校验（在航中/同地/无航路/燃料不足）+ 扣油 + 写启航事件 |

### 2.4 fc-world：支撑改动

- `Location.Ship` 从全 final 改为**受控可变**（voyage/parent/cargo），只允许 tick 阶段通过 `beginVoyage/advanceVoyage/arrive/setCargo` 修改。
- 新增 `LocationOps.withStock`：sealed + 模式匹配集中重建地点实例（库存阶段用）。
- `LocationGraph.replace/ships/placeCount`。
- `WorldState` 增加父子层级表（层级来自配置，不进 sealed 模型）。
- `GameEvent` 新增 `ShipDeparted` / `ShipArrived`（舰队事件）。

### 2.5 fc-cli：命令与星图

- 新增命令：`map`（ASCII 星图）、`ships`（舰队）、`sail <舰船> <目标>`（id 或名字均可）。
- `StarMapRenderer`：星系 → 行星/殖民地 → 设施的分层星图，标注库存、每日产出、航线天数/油耗、舰队位置。

---

## 三、运行示例

```
世界：边境纪事·启程，地点 16 个，航线 16 条，舰船 3 艘
Tick 阶段：time → movement → inventory
当前 SD 0（2200-01-01）

> sail 1 4
启航：晨星号 1 -> 4，预计 4 天，耗油 10
> next 4
时间推进 4 天，当前 SD 4（2200-01-05）；新增事件 1 条
> ships
  #1 晨星号 停泊于 Vega 织女  燃料=110
> sail 1 1
启航：晨星号 4 -> 1，预计 4 天，耗油 10
> next 4
时间推进 4 天，当前 SD 8（2200-01-09）；新增事件 1 条
  #1 晨星号 停泊于 Sol 索尔  燃料=100
```

星图（节选）：
```
[1] Sol 索尔 星系  库:supply=200,food=150  ◈晨星号
  ├ [2] Terra 泰拉 行星  库:food=300,ore=80
  ├ [3] Luna 站 空间站  库:supply=120,fuel=90
      航线: ->Vega 织女(4天,油10)  ->Deneb 天津四(6天,油15) ...
```

---

## 四、设计要点

1. **数据驱动落地**：核心代码零硬编码地点/航线/船；换 `--world` 即换世界（验收 1 已证明）。
2. **守恒语义**：产出同时加到地点分区视图与权威账本；启航扣油只从船货舱扣（M3 起并入统一账本结算）。
3. **确定性**：管线阶段顺序固定；移动只在 tick 内推进，事件时间戳取当时 Stardate。
4. **时间模型**：全程只用 Stardate，未引入现实时间（符合 `time-model-spec.md`）。

---

## 五、M1 未覆盖（按排期属后续批次）

- 船员自主行为（M2）、十二阶段完整管线（M2）
- 经济结算/贸易定价（M3）、殖民地生产配方（M3-M4）
- 技术树（M4）、势力外交（M4）
- 加密存档（M5）、联机聊天室（M5-M6）、桌面客户端（M6）

---

## 六、下一步（M2 主体与事件）

M2 目标：船员/居民/使者自主决策循环、完整十二阶段 Tick 管线、确定性随机流、事件分发（tick 内同步 + tick 后 Flow 通知）、并发 tick（虚拟线程）。

M2 前置：`fc-world.Agent.tick` 从空实现改为接入只读 `WorldView`；引入 `RandomGeneratorFactory` 的可重放随机流（同种子两次结果一致）。
