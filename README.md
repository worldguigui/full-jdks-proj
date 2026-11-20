# Frontier Chronicle（边境纪事）

一个基于 Java SE 21 的命令行星际远征模拟游戏。玩家指挥一支失联的远征舰队，在持续演化的星域中航行、贸易、生产、建设前哨站，并与自主行动的船员、居民和势力共享同一条时间轴。

项目同时是一项 Java SE 21 工程实践：在功能真实、完整、合理的前提下，尽量充分地使用标准 API；核心编译期与运行期不引入第三方依赖，不使用 Preview/Incubator 特性。

**当前可玩范围是 M0–M3**：配置驱动的星域与舰队、十二阶段逐日模拟、自主主体与事件、配方生产、市场交易、殖民地升级与补给线。科研、外交动作、存档、Mod 与联机尚不在当前范围内。

## 已实现内容

- **配置驱动的世界**：默认世界包含 16 个地点、17 条航线、3 艘舰船、36 个主体、4 个势力和 11 种货物；地点、航线、库存、主体、势力、价格与配方均由 XML 定义，启动时经过 XSD 与语义校验。可用 `--world <文件>` 装载其他世界。
- **逐日世界模拟**：一次 tick 推进一个星历日，固定执行十二个阶段：时间、环境、势力、感知、决策、工作、航行、消耗、市场、事件、编年史、报告。存在待决决策时，时间暂停。
- **自主主体**：船员、居民、使者是同一 `Agent` 的三种角色。主体会根据饥饿、疲劳和士气决定进食、休息、工作、沿航线迁徙或拒绝指令；迁徙逐日推进，不会瞬移。
- **舰队与航行**：按航路总天数寻路；启航消耗燃料，舰船在航期间逐日推进，抵达后才能再次启航或交易。
- **经济与生产**：11 种货物独立定价，库存低于目标时价格上涨，买卖存在价差；交易按库存、买方资金与请求量部分成交。地点的 8 条配方按输入、工人和工期生产，缺料、缺工人或停产窗口会阻止开工。工资、贷款利息与所有货物/资金转移都记账并可核对守恒。
- **势力与殖民地**：势力按自己的优先货物和资金在星域内采购运输。玩家前哨站可消耗货物与信用点升级为殖民地；补给线按“起点—终点—资源—批量—间隔”定期发运。
- **事件与历史**：离子风暴会中断生产、抬高燃料/能源/材料价格并可能触发救援决策；重大结果进入可查询的编年史。`hash` 输出世界状态 SHA-256，同种子与同操作序列得到相同结果。

## 快速开始

### 环境

- JDK 21（本项目在 Microsoft Build of OpenJDK 21.0.11 上验证）
- Maven 3.9+（在 Maven 3.9.16 上验证）

### 构建、测试与运行

```bash
# 确认 java 版本为 21
java -version

export JAVA_HOME=/path/to/jdk-21

# 运行全部测试
mvn -B test

# 打包为单文件可执行 jar
mvn -B package

# 启动默认世界
java -jar fc-runtime/target/frontier-chronicle.jar

# 启动其他世界
java -jar fc-runtime/target/frontier-chronicle.jar --world path/to/world.xml
```

也可以在 IDE 中直接运行 `fc.runtime.Main`；仓库自带 IDEA 运行配置 `Frontier Chronicle`。

Windows 下若控制台中文乱码，程序会在 stdout 非 UTF-8 时自动切换编码；如需关闭，加 `-Dfc.stdout.utf8=false`。

### 第一次游玩

启动后输入 `help` 查看全部命令。一个最小循环：

```text
map
market
trade quote food
trade buy food 20
sail 1 10
next 9
ships
trade sell food 20
log 10
goals
```

`map` 查看星图与舰队位置，`market` 查看当前地点行情，`sail 1 10` 让 1 号舰船前往 10 号地点，`next` 推进世界时间。若中途出现待决决策，时间会提前停止，按提示用 `decisions` 和 `decide` 处理。

## 命令

| 类别 | 命令 | 说明 |
|---|---|---|
| 查看 | `map` / `ships` / `crew` | 星图、舰队、主体名册 |
| 查看 | `market [舰船]` / `factions` / `colonies [地点]` | 行情、势力、殖民地 |
| 查看 | `decisions` / `goals` / `finance` | 待决决策、阶段目标、财务流水 |
| 查看 | `trace [条数]` / `log [条数\|all]` / `status` | 重大事件追踪、编年史、世界概况 |
| 校验 | `hash` | 当前状态哈希 |
| 行动 | `sail <舰船> <目标>` | 启航 |
| 行动 | `trade buy\|sell <资源> <数量> [舰船]` | 买卖；`trade quote <资源>` 查询报价 |
| 行动 | `next <天数>` / `decide <决策id> <选项id>` | 推进时间 / 处理决策 |
| 行动 | `upgrade <地点>` | 升级玩家殖民地 |
| 行动 | `supply list\|add\|cancel ...` | 查看、建立、取消补给线 |
| 其他 | `help` / `quit`（`exit`） | 帮助 / 退出 |

舰船与地点既可写 id，也可写名称。

## 项目结构

| 模块 | 职责 |
|---|---|
| `fc-world` | 世界模型、库存与财务账本、事件类型、`WorldState`、时间 |
| `fc-config` | XML 世界定义、XSD/语义校验与装载 |
| `fc-sim` | 十二阶段 Tick 管线、主体决策、事件队列、RNG、状态哈希、领域 SPI |
| `fc-fleet` / `fc-economy` / `fc-colony` / `fc-diplomacy` | 航行寻路、生产/市场/交易、殖民地/补给线、势力采购 |
| `fc-history` / `fc-cli` / `fc-runtime` | 编年史、命令与面板、装配与启动 |
| `fc-testkit` | 零依赖注解测试运行器 |
| `fc-science` / `fc-persist` / `fc-extensions` / `fc-audit` | 后续阶段的骨架模块，当前不参与可玩流程 |

依赖方向为 `fc-world ← fc-config ← fc-sim / 领域模块 ← fc-cli / fc-runtime`；领域模块之间不互相依赖，跨域协作通过 `WorldState`、SPI 与 `GameEvent`。详细架构见 `docs/architecture.md`。

## 测试

```bash
mvn -B test
```

当前共 106 个用例，分布在 8 个套件：

| 套件 | 用例数 | 范围 |
|---|---:|---|
| `WorldSmokeTest` | 12 | 时间、库存、账本、事件、关系 |
| `ConfigTest` | 23 | 默认世界规模、XML/XSD 与语义校验 |
| `M1TickTest` | 8 | 世界装配、十二阶段顺序、自然产出、航行 |
| `M2SimTest` | 18 | 随机流、事件队列、主体决策、工作单元、决策暂停、哈希 |
| `VoyageTest` | 7 | 寻路、燃料、启航拒绝、抵达 |
| `ColonyTest` | 11 | 升级条件、补给线发运与取消 |
| `EconomyTest` | 20 | 定价、冲击、交易守恒、配方生产、工资、利息 |
| `RuntimeSmokeTest` | 7 | 启动装配、命令、同种子哈希 |

测试使用项目自带的 `@FcTest` 运行器，由 Maven 的 `exec` 插件在 `test` 阶段执行；测试主类失败时以非零码退出。测试设计、覆盖边界与当前缺口见 `docs/testing.md`。

## 文档导航

| 文档 | 内容 |
|---|---|
| `docs/design-brief.md` | 立项任务书：项目目标、世界概念与工程约束 |
| `docs/architecture.md` | 架构、状态所有权、Tick 阶段、确定性与未决设计 |
| `docs/specs/m0-foundation.md` | M0 基础模型与构建约束 |
| `docs/specs/m1-world-and-fleet.md` | 世界配置、地点、航线、舰船与航行 |
| `docs/specs/m2-agents-and-events.md` | 主体决策、事件、风暴、势力与待决决策 |
| `docs/specs/m3-economy-and-colonies.md` | 定价、交易、生产、殖民地、补给线 |
| `docs/adr/` | 统一世界模型、确定性 Tick、守恒账本、配置驱动、模块边界、零依赖六项决策 |
| `docs/testing.md` | 测试运行方式、维度与未覆盖范围 |
| `docs/phase1/api-inventory.md` | Java SE 21 API 冻结清单（冻结数据，不应修改） |
| `docs/phase2/game-design.md` | 游戏设计稿 |
| `docs/phase3/project-direction.md` 等 | 项目方向、时间模型、命名、准入与未覆盖决策 |

`docs/phase2` 与 `docs/phase3` 中仍有尚未进入 M0–M3 的设计（如完整外交、科研、载具容量与风险结算）；这些内容不代表当前已实现能力。架构层面尚未定案的条目集中列在 `docs/architecture.md` 第 6 节。
