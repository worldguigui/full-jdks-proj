# 架构

Frontier Chronicle（边境纪事）是一个纯命令行、回合制的星际远征模拟游戏。本文件说明各模块如何协作、世界状态由谁拥有、一次回合如何结算，以及哪些不变量不可破坏。

## 1. 设计目标

1. **一个世界，一种规则**：星域、舰队与殖民地不是三个子游戏，而是同一套位置、主体、库存、生产、事件与时间机制在不同尺度上的实例。
2. **世界自己会动**：船员、居民与势力都是带需求与目标的自主主体；玩家推进一天，世界沿同一条时间轴结算。
3. **历史可查询**：所有重要结果写入同一部编年史，可以按时间与类型检索。
4. **确定性可重放**：同一种子与同一操作序列必须得到同一状态哈希。
5. **零第三方依赖**：编译期与运行期只使用 Java SE 21 正式 API，不使用 Preview/Incubator 特性。

## 2. 统一世界模型

五个抽象承载全部世界规则，定义都在 `fc-world`：

| 抽象 | 职责 |
|---|---|
| `Location`（sealed） | 可容纳主体与库存的空间节点；`Ship` 是移动的 `Location`，`Colony`/`Facility` 与恒星系、行星、轨道站、异常点并列 |
| `Link`（sealed） | 两个 `Location` 之间的通行边，带天数、风险与成本；航线按无向边参与寻路 |
| `Agent` | 有角色（`CREW`/`RESIDENT`/`ENVOY`）、需求、性格与技能的行为主体；角色不是子类 |
| `Stock` + `ResourceLedger` | 货物账本。`ResourceLedger` 是权威数据，`Stock` 是某地点的不可变分区快照 |
| `WorkUnit` + `Recipe` | 输入资源、工作天数、产出资源的统一生产单位 |

`Location`、`Link`、`GameEvent` 的 sealed 根与全部 permitted 类型必须同处 `fc-world`（JPMS/JLS 约束）。领域模块只提供服务与结算行为，不新增 sealed 子类型。两个 `non-sealed` 扩展口 `ExtensionLocation`、`ExtensionEvent` 为后续 Mod 机制保留；M5 的 Mod 加载器已经落地，但 Mod 当前通过 SPI 与命令/服务交互，尚未注册新的 sealed 子类型。

### 2.1 状态所有权

`WorldState` 是唯一的可变世界根，持有：

- 地点图（节点按 id 排序）、主体表（按 id 排序）；
- 货物账本与财务账本；
- 势力、关系矩阵、声望、殖民地；
- 在途运输、补给线、工作单元、待决决策；
- 风险修正、停产窗口、价格冲击；
- 船员技能、知识状态、科技、勘察任务、船坞订单、协定、委托、工程与终局；
- 当前 `Stardate` 与编年史接口。

`GameLoop` 与 `TickPipeline` 位于 `fc-sim`，持有对 `WorldState` 的引用并驱动结算；`Chronicle` 只留接口在 `fc-world`，实现在 `fc-history`。这样 `fc-world` 保持最底层、无业务依赖。

### 2.2 货物与信用点

- 货物共 11 种：燃料、能源、食物、水、矿物、金属、材料、设备、药品、奢侈品、科研数据。
- 信用点不是货物，只存在于 `MoneyLedger` 的账户中，不允许写进 `Stock`。
- 库存不允许为负；`consume` 在不足时失败，`tryConsume` 返回 `false` 且不改变状态。
- 唯一允许增加货物总量的操作是生产（包括环境再生）；唯一允许减少总量的操作是消耗。转运、补给线与运输在两端记账，总量不变，并由账本守恒断言兜底。

## 3. 时间与回合

- 1 tick = 1 星历日；`Stardate` 是世界时间的唯一来源。
- 游戏时间（星历）决定状态、编年史与重放；系统时钟只用于日志与监控，绝不进入世界状态。
- `next <n>` 逐日执行管线。出现待决决策时立即停止推进，直到玩家用 `decide` 处理完毕。

### 3.1 十二阶段 Tick 管线

| # | 阶段 | 主要职责 |
|---|---|---|
| 1 | 时间推进 | `Stardate++`，采样天气/环境，清理到期的价格冲击、停产窗口与风险修正；记录目标判定所需快照 |
| 2 | 环境与生态 | 地点自然产出（处于停产窗口时跳过）、殖民地维护、设施容量约束 |
| 3 | 势力 AI | 势力采购其优先货物；生成委托；势力间关系确定性漂移 |
| 4 | 主体感知 | 刷新饥饿、疲劳、士气；舰上主体与船员状态 |
| 5 | 主体决策 | 进食、休息、工作、科研、勘察、维修、觅食迁徙、拒绝指令 |
| 6 | 生产与工作 | 技能化配方开工、工作单元推进、科研项目、船坞订单、维修与终局工程 |
| 7 | 航行与物流 | 舰船航程推进与风险事故、勘察进度、在途运输抵达、补给线发车 |
| 8 | 消耗与维护 | 口粮消耗、贷款利息、设施维护 |
| 9 | 市场与财务 | 工资、组织收支、市场结算、税率拆分 |
| 10 | 事件引擎 | 配置化事件、离子风暴生成与传播、冲突结算、固定反应链、待决决策 |
| 11 | 编年史落库 | 将本 tick 的有序事件写入编年史 |
| 12 | 报告聚合 | 生成回合报告行、D0–D4 目标阶段与终局判定 |

阶段顺序固定，下游阶段读取上游刚写入的真实状态。领域系统通过 `fc-sim` 中的 SPI（`ProductionSystem`、`MarketSystem`、`FactionSystem`、`ColonySystem`、`ConflictSystem`、`GoalSystem`）注入，避免 `fc-sim` 反向依赖领域模块。M4 的事件是**不可变事实**，跨系统后果只在阶段 10 内按固定顺序调用领域服务，不引入异步状态写入或第二套执行引擎。

### 3.2 确定性

- 世界内所有遍历使用有序结构或显式排序：地点按 id、主体按 id、舰船按 id、运输按 id、编年史按入队顺序。
- 随机数来自 `RngStreams` 按子系统派生的独立流（天气、事件、主体、市场、势力）；同种子重放不受其他子系统调用次数影响。
- tick 内事件进入有序 `EventQueue`，只在阶段 11 落库。
- `EventBus`（`java.util.concurrent.Flow`）只在 tick 结束后广播，供报告/遥测读取，不参与状态写入。
- `StateHasher` 对可观察状态按确定顺序序列化后求 SHA-256，作为重放验收依据。
- 存档复用同一确定性：保存世界来源、状态哈希与有序命令日志；读档重新装配并重放，
  哈希不一致即拒绝。RNG 流由种子重新派生，因此不需要单独序列化流状态。
- Mod、宏、PNG、JMX 与 HTTP 都是只读观察或外围能力，不写入世界状态，不进入哈希。
- 编年史查询/导出/只读 JDBC 只是历史事实的读取视图：不改变世界状态，也不改写编年史。
  `query`、`export` 与存档命令一样不进入重放日志，避免读档时重复写出文件。

### 3.3 编年史查询与导出

编年史是只可追加的事件事实流，因此读取侧不直接暴露 `GameEvent` 的 sealed 分支，而是投影为
`EventView`（日、日期、类型、类别、地点、主体、因果、结果、摘要）。三种读取入口共享同一投影：

- **对象查询**：`ChronicleQuery` 过滤日区间、类别、类型、地点、主体与全文，稳定排序后分页；
- **文件导出**：`ChronicleExport` 输出 CSV、确定性 StAX XML、XSLT HTML 与 ZIP 证据包；
  XML 由内置 XSD 校验，计数用 XPath；解析关闭 DOCTYPE、外部实体与外部样式表访问；
- **SQL**：`jdbc:fc:chronicle:<会话>` 只读驱动把 `SELECT ... FROM chronicle` 编译为同一过滤/排序/
  分页模型，支持 `PreparedStatement`、`LIKE`、`COUNT(*)` 与结果集/数据库元数据。

CLI 只做薄封装：`query <SQL>` 绑定匿名会话后执行，`export <格式> [路径]` 写文件。两者都拒绝
写入世界状态，且不进入存档的重放命令日志。

### 3.4 星图工坊

`MapAtlas` 是只读的呈现层：输入是 `LocationGraph` 与选项，输出是内存图像或 PNG/JPEG 文件。
布局仍按地点 id 排序，星野使用固定种子，因此同一世界逐像素一致；字体栅格化由平台提供，
不影响几何布局。图像管线包含：

- 背景渐变、固定种子星野、模糊星云（`ConvolveOp`）与亮度调整（`RescaleOp`）；
- 曲线航线（`QuadCurve2D`/`CubicCurve2D`）与按地点类型分派的节点形状；
- 字形轮廓标题（`GlyphVector`）、字距副标题（`TextAttribute`）、`Area` 图例描边与罗盘；
- 缩略内嵌图（`AffineTransformOp`）、比例尺与 `FontMetrics` 定位；
- PNG tEXt / JPEG COM 元数据写入与读回，JPEG 前用 `ColorConvertOp` 转 sRGB。

`atlas` 命令是它的 CLI 包装；制图会写文件，因此与 `export` 一样排除在重放日志之外。

### 3.5 档案签章

`EvidenceSignature` 用 XML Signature（JSR-105）给任意导出文件做完整性签章。产物是自包含
证据 XML：负载 Base64 封装在 `urn:fc:evidence` 的 `chronicle` 元素，`ds:Signature` 引用
`#chronicle`，摘要 SHA-256、签名 RSA-SHA256、独占 C14N，`KeyInfo` 内嵌 RSA `KeyValue`。

- 验签分两级：内嵌公钥只证明「同一把钥匙签的」；传入可信公钥才验证身份，指纹用于带外核对。
- 密钥以 X.509/PKCS#8 DER 落在本地密钥目录，首次生成、之后复用；私钥文件权限由部署方负责。
- 加载与验签都关闭 DOCTYPE/外部实体；签名不改变世界状态，命令不进入重放日志。

### 3.6 配乐工坊

`Soundtrack` 把世界状态映射为一段确定性配乐：种子来自 `StateHasher` 摘要与当前星历，
`Random(seed)` 驱动五声音阶旋律、铺底三和弦与打击乐，因此同一状态必然得到同一份 MIDI。

- 曲式固定为 4 小节 4/4、PPQ 480、96 BPM，共三条轨道：长笛主旋律（program 73）、
  铺底 pad（program 89）与第 10 通道打击乐；写入曲名、速度（`0x51`）、拍号（`0x58`）、
  音量/声像控制器与 EOT。
- `MidiSystem.write` 输出 SMF type 1；`readMidi`/`format` 用于回读校验。
- `renderWav` 是离线正弦合成器：按十二平均律换算频率，加起音/释音包络后混音、限幅，
  经 `AudioSystem.write` 写 44.1 kHz/16-bit/单声道 WAV，不需要声卡。
- `sequencerAvailable()` 只做设备探测，无设备时返回 `false`；播放交给外部 MIDI 播放器。
- CLI `music [mid|wav] [路径]`（别名 `soundtrack`）会写文件，因此与 `export`/`atlas` 一样
  排除在重放日志之外。

### 3.7 档案库

`ChronicleVault` 是编年史的本地镜像层：每个快照就是一份 ZIP 证据包，按内容寻址命名
（`chronicle-<日>-<SHA-256 前 12 位>.zip`），索引 `index.tsv` 逐行记录文件、日、字节数、
摘要、事件数与 CRC32。

- **并发安全**：`.vault.lock` 上 `FileChannel.tryLock` 做跨进程排他；索引追加自身也加锁。
  锁被占用时明确失败，不做阻塞等待。
- **异步落盘**：快照字节由 `AsynchronousFileChannel` 分批写入，`CompletionHandler` 汇总
  错误，`CountDownLatch` 收口后 `force(true)`；索引行在所有数据落盘后才追加。
- **读取**：索引用 `MappedByteBuffer` + 严格 UTF-8 解码；`verify` 用直接 `ByteBuffer`
  流式计算 SHA-256 并核对字节数，缺失与篡改都给出逐份结论。
- **监视**：`WatchService` 等待新快照出现，过滤索引、锁和临时文件；供外部归档器联动。
- CLI `vault [write|list|check|watch] [目录]`（别名 `archive`）会写文件或阻塞等待，
  因此与 `export`/`atlas`/`music` 一样排除在重放日志之外。

### 3.8 证据封存

`EvidenceSeal` 负责机密性：把文件封成只能由持有同一对称密钥的一方打开的 `.sealed` 包。

- 密钥来自 PKCS#12 密钥库中的 AES-256 `SecretKeyEntry`（别名 `evidence`）；首次使用生成，
  之后复用。密钥库口令由调用方提供，CLI 默认口令仅用于本地演示并可用系统属性覆盖。
- 封存用 `SealedObject` + `AES/GCM/NoPadding`：GCM 同时保证机密性与完整性；随机 IV 使同一
  明文的两次封存不同。文件前缀 `FCSEAL1` 用于快速拒绝非本程序产物。
- 读取时先校验魔数，再以 `ObjectInputFilter` 白名单反序列化（仅 `SealedObject`、`String`、
  `byte[]`），防止把任意序列化流当输入。
- 文件写出走临时文件 + 原子替换；POSIX 文件系统上密钥库权限收紧为属主读写。
- CLI `seal`/`unseal` 会读写文件，排除在重放日志之外。

### 3.9 回环 JMX 管理端点

`RemoteJmx` 把平台 `MBeanServer` 通过本机 RMI 注册表暴露给 `jconsole` 等标准 JMX 客户端。

- 注册表与连接器分别使用自建的 `RMIServerSocketFactory` / `RMIClientSocketFactory`，
  两端都固定 `InetAddress.getLoopbackAddress()`；不依赖 `java.rmi.server.hostname`，
  也不做跨机暴露。认证边界由操作系统本地账户承担。
- `Server` 是 `AutoCloseable`：`close()` 停止连接器并注销注册表，可重复调用；启动中途失败
  会清理已创建的资源。
- `snapshot` 读取 `fc.telemetry:type=Game` 的八个属性；`setAttributes` 返回实际写入的属性，
  只读 MBean 得到空列表。
- CLI `jmx [start [端口]|status|stop]`（`jmx <端口>` 等价启动）会创建网络端点，排除在重放日志之外，
  退出时由 `closeJmx()` 统一清理。

### 3.10 回环聊天室中继

`ChatRelay` 实现联机通信的第一阶段——通信基础设施：一条只绑定回环地址的 TCP 消息面，
加上一个只供管理工具使用的 RMI 控制面。

- 协议是 UTF-8 行文本。客户端先 `NICK` 注册，随后 `MSG` 广播；`WHO`、`HIST n`、`PING`、
  `QUIT` 用于查看与退出。服务端推送 `JOIN`/`PART`/`MSG`/`ROSTER`/`HISTORY`/`ERROR`，
  消息带全局递增序号，便于脚本按序消费。
- 每条连接由虚拟线程处理；成员、昵称占用与会话用 `ConcurrentHashMap` 维护，成员号与
  消息序号由 `AtomicLong` 分配。昵称按小写去重，消息与历史长度都有显式上限。
- 历史保存在进程内最近 `historyLimit` 条（默认 200），不落盘；系统公告以 `id == 0` 标记。
- 控制面把成员表、消息计数与广播通过本机 RMI 注册表暴露为 `fc-chat-control`；注册表与
  导出对象复用回环套接字工厂，不做跨机暴露。
- CLI `chat open [端口]|say <昵称> <文本>|history [条数]|roster|close`；`say` 走真实 TCP
  往返。命令与端点生命周期不进入重放日志，退出时统一关闭。

### 3.11 fc:// 统一资源命名

`FcNaming` 按 `docs/phase3/naming-spec.md` 实现进程内 JNDI 命名上下文。

- 名称格式 `fc:<scope>/<path>`；顶级 scope 固定为 `mod`/`config`/`save`/`service`/
  `chronicle`，未知 scope 与非法路径段在解析期拒绝。
- 自研 `InitialContextFactory` 从环境取独立根；`InitialContext` 不依赖外部目录服务。
  支持相对名（相对当前上下文）与绝对名（以 `fc:` 开头）。
- `bind`/`rebind`/`unbind`/`rename`/`createSubcontext`/`destroySubcontext` 覆盖上下文生命周期；
  `list`/`listBindings` 返回 `NamingEnumeration`；`linkRef` 由 `lookupLink` 解引用。
- `FcService` 实现 `Referenceable`：命名树里存 `Reference`，查找时由
  `NamingManager` + 自研 `ObjectFactory` 重建；避免直接持有实现实例。
- 绑定/解绑/重绑向 `NamespaceChangeListener` 发送 `NamingEvent`。
- CLI `res list|locate|register|unregister|services`；`chat`/`jmx` 端点自动登记为
  `fc:service/chat` 与 `fc:service/jmx`，停止时解除。命令不进入重放日志。

### 3.12 TLS 加密信道与证书信任

远程端点默认只绑定回环地址；`TrustAuthority` / `SecureChannel` / `CertificateChains`
把「本地可信 ≠ 远程可信」落成可验证的证书边界。

- `TrustAuthority` 只用公开 API 生成 RSA 密钥并按 RFC 5280 手工做 DER 编码，
  签发自签根、中间 CA、服务端与客户端叶子证书；不接受 JDK 内部的
  `sun.security.x509`。
- 叶子证书带基本约束、密钥用途与扩展密钥用途（serverAuth/clientAuth）；
  服务端证书带 `localhost` 主题备用名。
- `CertificateChains` 用 PKIX 构建与校验链：`CertPathBuilder` 从候选池找路径，
  `CertPathValidator` + 显式 `TrustAnchor` 校验签名、有效期与用法；
  吊销检查关闭（自签信任库没有 CRL/OCSP）。
- `SecureChannel` 统一 PKCS#12 密钥库与 `SSLContext`：私钥条目带链，信任库只放根；
  握手后可从 `SSLSession` 取对端链并做指纹比对。
- 聊天中继支持 `SSLServerSocket` + `setNeedClientAuth(true)`；`chat open <端口> tls <目录>`
  的客户端必须持有信任库签发的证书，匿名客户端在握手阶段被拒。
- CLI `trust init|status [目录]` 生成并复核信任库；命令不进入重放日志。

### 3.13 Frontier Script

JDK 21 不附带脚本引擎。`FcScriptEngine` 是 `javax.script` 的自研实现，由
`FcScriptEngineFactory` 经 `META-INF/services` 注册，供 `ScriptEngineManager` 发现。

- 语法：`#` 注释、`;`/换行分隔语句、`set`、`echo`、`repeat <n> <一行>`、
  普通行交给注入的 `Host` 执行；`${name}` 在求值期替换，未知变量报错。
- `AbstractScriptEngine` 提供上下文与绑定（`SimpleBindings`）；`Compilable`
  返回可复用的 `CompiledScript`，语法错误在编译期暴露，并带文件名/行号。
- `repeat` 上限 1000 次、嵌套上限 8 层；宿主异常转为 `ScriptException`。
- CLI `script engines|eval|run`：引擎每次新建，`Host` 回调 `Cli.execute`。
- 重放：脚本内的每条命令在**执行前**交给 `Cli.recordCommands`，由会话按普通命令
  规则过滤后写入存档；`script` 本身不记录。读档只重放展开后的命令，
  不依赖脚本文件仍然存在。

## 4. 模块与依赖

```
fc-world ← fc-config ← fc-sim ← 领域模块（fleet/colony/economy/science/diplomacy…）
                                   ↑
                             fc-cli / fc-runtime
fc-persist ← fc-runtime        fc-extensions ← fc-cli / fc-runtime
fc-audit 扫描全部模块的 target/classes（反应堆末尾）
```

| 模块 | 当前职责 | 状态 |
|---|---|---|
| `fc-world` | 统一世界模型、账本、事件类型、`WorldState`、`Chronicle` 接口、时间、技能/知识/科技/协定/委托/工程 | 已实现 |
| `fc-config` | 世界配置模型、XML/XSD 校验装载、世界装配输入 | 已实现 |
| `fc-sim` | Tick 管线、游戏循环、主体决策、事件队列/总线、RNG、状态哈希、六个领域 SPI | 已实现 |
| `fc-fleet` | 航路寻路、启航与燃料校验、航程风险、勘察、船员调动、维修 | 已实现 |
| `fc-economy` | 配方生产、供需定价、税率、市场结算、玩家交易、借贷 | 已实现 |
| `fc-colony` | 殖民地升级、补给线、实验室/船坞、终局工程 | 已实现 |
| `fc-science` | 科技树、科研项目、效果叠加、勘察服务 | 已实现（M4） |
| `fc-diplomacy` | 势力采购、声望与条约、委托、冲突结算、D0–D4 目标 | 已实现（M4） |
| `fc-history` | 内存编年史、查询模型、导出、只读 JDBC 驱动、XML 签章、档案库镜像、PKCS#12 证据封存 | 已实现（M5+） |
| `fc-cli` | 命令解析、星图、面板与报告 | 已实现 |
| `fc-persist` | 存档仓库、偏好、本地化 | 已实现（M5） |
| `fc-extensions` | Mod SPI/装载器、运行时编译、宏、PNG、星图工坊、配乐工坊、JMX 注册与回环远程管理、聊天室中继、fc:// 命名上下文、TLS 与证书信任、Frontier Script、HTTP、音频 | 已实现（M5+） |
| `fc-runtime` | 装配、REPL、存档/读档、PNG、Mod 装载、遥测与状态端点、单文件 jar | 已实现 |
| `fc-testkit` | 注解 + 反射的零依赖测试框架 | 已实现 |
| `fc-audit` | API 覆盖审计（classfile 常量池 → 证据） | 已实现（M5） |

依赖方向：`fc-world ← fc-config ← fc-sim / 领域模块 ← fc-cli / fc-runtime`。领域模块之间不互相依赖；跨域协作通过 `WorldState` 与 `GameEvent`。`fc-persist` 只依赖 JDK；`fc-extensions` 依赖 JDK 与 `fc-world`/`fc-sim`/`fc-config`（`fc-cli` 仅为 `atlas` 制图与 `music` 配乐依赖它，无反向依赖）；`fc-audit` 独立。`fc-testkit` 只作为测试作用域依赖。`fc-audit` 位于反应堆最后，确保审计能读取全部模块产物。

## 5. 运行时装配

`fc-runtime` 的 `RuntimeSession` 完成一条固定启动链：

1. 读取 `world/default-world.xml`（或 `--world <path>`），经 XSD 与语义校验生成 `WorldDefinition`；
2. `WorldAssembler` 装配地点图、舰船、主体、技能、货物/财务账本、势力、殖民地、初始知识、船型与设施；
3. 以配置参数构造 `Economy` 与生产、科研、市场、势力、殖民地、冲突、目标各领域系统，注入 `TickPipeline.m4`；
4. 创建 `GameLoop`、`Cli`、`EventBus`，打印启动横幅；
5. 扫描工作目录 `mods/` 装载扩展（单个失败只告警）；
6. 注册 JMX `fc.telemetry:type=Game`；若给出 `--serve[=端口]`，在回环地址启动只读状态端点；
7. 进入 REPL；`save`/`load`/`saves`/`png` 由运行时拦截，其余命令交给 `Cli`。

存档/读档复用同一 `assemble` 路径：读档时按存档中的世界来源重新装配，再按序重放命令并核验哈希。所有世界数据来自配置，核心代码不硬编码地点、舰船、货物或配方。`RuntimeSession` 与 REPL 分离，使集成测试可以复用完全相同的装配路径。

## 6. 设计决策现状

M4 规格（`docs/specs/m4-science-diplomacy-and-exploration.md`）已对此前的 12 项待定决策给出可执行结论并落地：六技能模型、初始殖民地口径、D1 声望门槛（知名 = 声望 ≥ 20）、配置化事件参数、航程风险结算、设施/岗位、税率与 `BigDecimal` 精度、舰船容量与船员、阶段 10 固定反应链、借贷命令。M5 又落地了存档、Mod、外围观察与审计。

以下条目仍未定案或明确留待后续批次，不应把计划中的能力当作已实现：

1. **工资循环**：工资从地点账户支付给家庭账户，但家庭账户的消费回流路径尚未设计；当前家庭账户只累积。
2. **设施实体化**：设施目前是地点属性（类型 + 工人容量），不是可独立建造、改装与摧毁的实体；设施挂载配方与岗位层级的完整模型未定案。
3. **事件扩展边界**：事件参数已配置化，但新增事件类型仍需代码；Mod 不能注册新的 sealed 事件子类型。
4. **存档迁移**：格式 v1 对不兼容版本显式拒绝，没有跨版本迁移器；长期存档兼容策略未定案。
5. **Mod 安全模型**：已有 API 版本与代码签名校验，但没有权限沙箱、依赖解析与远程仓库。
6. **联机与多人**：没有网络传输协议、权威服务器或并发状态合并；`--serve` 只是只读回环端点。
7. **内容生成与人物史**：程序化生成地点、招聘新主体、人物关系网、战术战斗与人员死亡仍在范围之外。
8. **覆盖率目标**：当前审计为类型 14.1%、包 43.2%、模块 13/20。后续按 `docs/phase3/data/api-mapping.csv`
   以真实功能为载体扩张，不设数字硬阈值，也不允许无意义 API 调用。

上述条目的处置必须遵循同一流程：先写规格，再实现与测试，最后生成审计证据并本地提交。
