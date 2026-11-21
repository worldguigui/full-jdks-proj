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
| 12 | 报告聚合 | 生成回合报告行、D0–D4 阶段目标与 V1–V3 胜利路线判定 |

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
  两端都固定 `InetAddress.getLoopbackAddress()`；连接器直接实例化
  `RMIConnectorServer` 并写入其标准套接字工厂属性，不依赖 provider 查找，也不依赖
  `java.rmi.server.hostname`，不做跨机暴露。认证边界由操作系统本地账户承担。
- `Server` 是 `AutoCloseable`：`close()` 停止连接器并注销注册表，可重复调用；启动中途失败
  会清理已创建的资源。客户端可用通用 `JMXConnectorFactory`，也可用
  `directConnect`（`RMIConnector`）。
- `snapshot` 读取 `fc.telemetry:type=Game` 的八个属性；`setAttributes` 返回实际写入的属性，
  只读 MBean 得到空列表。`allBeans`/`publishedBeans` 用 `QueryExp`（字符串 `match`）
  枚举与筛选 fc 域 MBean。
- CLI `jmx [start [端口]|status|stop|beans|heartbeat …]`（`jmx <端口>` 等价启动）会创建网络端点，
  排除在重放日志之外，退出时由 `closeJmx()` 统一清理。

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

### 3.14 JMX 通知、监视器与真实时间心跳

JMX 端点不只是属性快照：遥测 MBean 主动广播，监视器与定时器把「阈值」和「真实时间」
变成可订阅的事件。全部能力仍只在本机回环端点内可用。

- `GameTelemetry` 继承 `NotificationBroadcasterSupport` 并实现 `NotificationEmitter`：
  `StateHash` 变化发出 `AttributeChangeNotification`（类型 `jmx.attribute.change`），
  命令计数变化发出 `fc.telemetry.command`（`userData` 含命令数与游戏日）；
  `getNotificationInfo` 声明两类通知。
- `FrontierCatalog`（`fc.telemetry:type=Catalog`）是 `DynamicMBean`，用
  `MBeanInfo`/`MBeanAttributeInfo`/`MBeanOperationInfo` 自描述；`snapshot` 返回
  `CompositeData`（OpenType `GameSnapshot`），`find` 按 JMX 域枚举名称。
- `TelemetryMonitor` 注册 `GaugeMonitor`（`fc.telemetry:type=Gauge`），采样间隔 100 ms，
  阈值类型与被观测属性一致；越过阈值即广播 `jmx.monitor.gauge.high/low`，无需轮询。
- `Heartbeat` 注册 `Timer` MBean（`fc.telemetry:type=Heartbeat`），按秒广播
  `fc.heartbeat`；游戏时间（Tick）与真实时间严格分离，心跳不改变世界状态。
- CLI `jmx beans` 用 `QueryExp` 在服务端筛选已发布状态的 MBean；`jmx heartbeat [秒|stop|status]`
  管理真实时间心跳。两者与 `jmx` 一样不进入重放日志。

### 3.15 XA 两阶段文件事务

`TwoPhaseFiles` 是本地文件的事务协调器，`FileXaResource` 是 `XAResource` 的文件实现；
用途是把同一份编年史导出双写到两个路径，避免一半成功、一半失败。

- 每个目标是一个分支：同一 `FcXid`（格式号 `0x4643`）的全局事务，分支号按资源序号生成；
  协调器按 XA 协议依次 `start`/`end`，全部 `prepare` 成功后逐个 `commit`。
- prepare 只创建目标父目录、写同目录临时文件并 `force`，再写
  `.fcxa-<global>-<branch>.xa` 恢复日志；目标文件保持不变。commit 用
  `ATOMIC_MOVE`（不支持时退化为替换移动）发布，随后删除日志。
- 任一 prepare 失败时，协调器回滚所有已准备分支；commit 阶段失败则报告
  `XA_HEURMIX` 启发式混合，因为已提交分支无法撤销。
- `recover` 扫描目标目录的恢复日志并重建 `Xid`，新进程可继续 commit 或 rollback；
  协议误用以 `XAER_PROTO`/`XAER_NOTA`/`XAER_INVAL` 拒绝，不静默忽略。
- CLI `export <格式> <路径A> <路径B>` 先在临时目录生成一次，再用该事务双写；
  单路径仍走同一条提交路径。`export` 不进入重放日志。

### 3.16 货单剪贴板与 Transferable

`CargoManifest` 是当前局状态的只读快照，实现 `java.awt.datatransfer.Transferable`；
`LocalClipboard` 提供不依赖窗口系统的进程内剪贴板。用途是让其他组件（未来的 UI、
Mod 或导出器）用标准 flavor 协议交换货单，而不是互相引用内部对象。

- 货单同时支持自定义 `application/x-fc-manifest;class=java.lang.String` 与
  `DataFlavor.stringFlavor`，只认文本的接收方也能消费；不支持的 flavor 抛
  `UnsupportedFlavorException`。
- `LocalClipboard` 继承 `Clipboard`，在 `setContents` 后补齐基类缺失的语义：
  通知旧 `ClipboardOwner.lostOwnership` 并派发 `FlavorEvent` 给
  `FlavorListener`；单个监听器异常不影响复制。
- `SystemFlavorMap`（`FlavorMap`/`FlavorTable`）把货单 flavor 登记为原生名
  `FC_MANIFEST`，`describe` 同时展示 MIME、可读名与原生映射；MIME 校验失败以
  `MimeTypeParseException` 报告。
- `StringSelection` 作为纯文本路径；CLI `manifest copy|paste|text|flavors|status`
  只操纵进程内状态，不进入重放日志，也不要求图形环境。

### 3.17 星际远征指挥终端（fc-tui）与双窗口模式

`fc-tui` 是独立客户端模块，不依赖 `fc-cli`/`fc-runtime`，只依赖 `fc-world`
与 `fc-history`。TUI 是 CLI 的可视化层：命令仍由 CLI 语义执行；长输出进入可滚动
页面，独立 CLI 窗口仍保留完整终端输出。

- **两种启动方式**：
  - `--tui`：单窗口 TUI，命令栏输入 CLI 命令，底部一行显示最后反馈；
  - `--tui --console`（或 `--split`）：双窗口模式。本窗口是**可交互 TUI**，
    另开一个独立 CLI 窗口；两个窗口都可输入命令，都连接同一个权威宿主。
    TUI 用可视化页面与状态行反馈，独立 CLI 窗口保留 `help`/`log all` 等
    **完整终端输出**；TUI 页面通过宿主通知自动刷新。
- **权威宿主**：`fc-runtime.ConsoleHost` 持有 `RuntimeSession`，在同步锁内执行命令、
  存档/读档/PNG 与遥测刷新；TUI 构建快照时使用同一把锁，避免读到半个世界。
  CLI 窗口通过 `SplitConsoleServer`（回环地址 + 随机令牌 + Base64 帧）提交命令，
  TUI 直接调用宿主；两者都在同一把锁内串行。`ConsoleClient` 打印完整输出。
  Windows 用 `cmd /c start` 打开新控制台，并先用生成的批处理执行 `chcp 65001`、
  以 UTF-8 读写，避免本地代码页导致中文乱码；其他平台打印可直接运行的手工命令；
  `FC_CONSOLE_NO_WINDOW=1` 用于测试/排障。
- **TUI 页面命令**：`map`/`ships`/`colonies`/`research`/`market`/`log`/`status`/
  `decisions`/`goals`/`help` 直接复用 CLI 命令名；`output` 是 TUI 局部页，
  展示本轮命令的完整输出。任一窗口执行页面命令后，宿主通知 TUI 刷新快照并
  切换视图。主窗口 `tui on` 与双窗口 TUI 使用宿主通知 + 100 ms 后台刷新，
  其他 CLI 窗口的命令会自动反映到 TUI。
- **滚动与长内容**：文本页、详情弹窗和报告面板有独立滚动偏移；`scroll up`
  /`scroll down [N]`/`scroll top`/`scroll bottom`/`page up`/`page down`
  完整单词命令（行模式输入，不依赖方向键）。标题显示 `1-20/57` 范围；
  列表页 `select` 自动把选中项滚入视口，`inspect` 详情可继续滚动。
  `help`/`goals`/`market`/`research` 报告与 `output` 页按面板宽度换行；
  表格单元格超宽用 `..` 收尾，完整值进入详情或 `output`。
- **渲染内核**：`TextWidth` 处理 CJK/emoji/组合字符宽度；`ScreenBuffer` 是字符 +
  样式双缓冲（宽字符占两列、控制字符清洗）；`DiffRenderer` 只输出变化单元格，
  首帧/尺寸变化清屏，绘制期间关闭自动换行；`CharSet` 提供 Unicode/ASCII 降级，
  `Style` 固定深空蓝黑 + 冷青 + 琥珀调色板。每次输入后全量重绘，清除行回显。
- **终端适配**：`Terminal` 抽象输出侧；`SystemTerminal` 使用备用屏幕、UTF-8、
  真彩 SGR，并探测行列（`FC_TUI_COLS/ROWS` 覆盖；Windows 优先 PowerShell
  `WindowSize`，其次 `mode con`；Unix 用 `stty size`；默认 100×30）。
  进入 TUI 后 `SizeWatcher` 守护线程每 2 秒后台探测尺寸，变化时置脏并重排；
  不需要重启或手动 `refresh`。`FC_TUI_COLS/ROWS` 仍在每次输入后立即生效。
  Java SE 21 无跨平台 Raw Mode，本版输入为行模式。输入必须使用阻塞
  `readLine()`，不能用 `InputStream.available()`/`Reader.ready()` 轮询：
  Windows 控制台在回车前可能始终返回 0，轮询会导致 TUI 完全收不到命令。
  共享 CLI Reader 时由主线程阻塞读行 + 守护刷新线程同步画面；双窗口主 TUI
  由守护输入线程投递队列，界面线程 100 ms 轮询，从而让宿主 `quit` 能立即结束
  界面，同时不会抢走共享 CLI 的后续输入。
- **待办阻塞**：`next` 因待决事项停止时，TUI 在状态行显示阻塞计数与
  `decisions`/`decide <id> <选项>` 路径并切到情报页；CLI 窗口同时收到完整原因。
  情报页展示决策 id 与选项 id，组织条显示待办计数。
- **生命周期**：单窗口 `quit`/EOF 恢复终端；双窗口由 CLI 的 `quit` 通知宿主停止，
  TUI 退出并关闭控制台服务。

### 3.18 运行时诊断 Agent（fc-agent）

`fc-agent` 是独立 Java Agent JAR，`Premain-Class=fc.agent.FcAgent`；
通过 `-javaagent:...-agent.jar[=limit=N]` 启动。它只添加观察型
`ClassFileTransformer`（返回 null，不修改字节码），注册
`fc.agent:type=Diagnostics` Standard MBean，暴露类加载/转换统计、重定义/重转换
能力、采样时间、类名样本与 `getObjectSize` 浅层大小（明确不是完整对象图）。
没有 `agentmain`/Attach，也不执行 redefine/retransform。Agent 未加载时游戏照常
运行，`diagnostics` 命令给出 `-javaagent` 启动提示。

### 3.19 SASL / Kerberos 认证中心（fc-auth）

`fc-auth` 不依赖核心模块，提供可选认证服务：`SaslAuthenticator` 用标准
`Sasl.createSaslClient/Server` 建立双方，内置 `FC-CHALLENGE`（nonce +
HMAC-SHA256，不传明文口令）并由 JCA Provider 注册；`LocalCallbacks` 处理
Name/Password/Realm/Authorize Callback；成功后返回统一 `AuthIdentity`。
`GssAuthenticator` 用 `GSSManager`/`GSSContext`/`Oid` 提供 Kerberos 上下文入口，
`KerberosEnvironment` 探测 realm/KDC/keytab/主体；无环境时明确未就绪，
GSS 失败保留错误码，绝不伪造认证成功。认证服务不参与单机启动。

### 3.20 离线编年史数据工作台（java.sql.rowset）

`ChronicleWorkbench` 复用只读 JDBC 驱动装载 `CachedRowSet`，连接关闭后仍可
分页、过滤（`FilteredRowSet` + `Predicate`）、复制排序、与地点目录
`JoinRowSet` 连接；`WebRowSet` 负责标准 XML 导入导出，每行带
`schema_version=fc-workbench/1`，导入时校验必需列/版本/元数据，非法输入以
`SQLException` 拒绝。CLI `workbench show|filter|sort|join|export|import`
提供用户入口，不进入重放日志。JDK 的 `FilteredRowSet.size()` 不反映过滤结果，
工作台以实际遍历计数；`CachedRowSetImpl` 插入/遍历顺序相反，内部逆序写入。

### 3.21 外缘星区与多路线胜利

默认世界扩展为 24 个地点、27 条航线：新增 Altair、Procyon 外缘星区及回声裂隙，
并在原有环线上增加可达航线；地点知识仍按 UNKNOWN/DETECTED/SURVEYED/CHARTED 推进。

`GoalService` 在原有 D0–D4 阶段目标之外提供三条可并行推进的胜利路线：

- **商业霸权**：信用点达到 10000；
- **外交联盟**：任一势力声望达到 60；
- **星域测绘**：全部地点达到 CHARTED（仅探测不够，必须完成测绘）；
- 原有 **工程终局**（星门/边境信标）仍保留。

失败条件先于胜利判定：

- **船员危机**：至少 6 名 CREW 同时处于饥饿、疲劳过度或士气低落；
- **债务违约**：玩家账户债务 ≥ 5000；
- **声望崩溃**：任一势力声望 ≤ -50。

任一胜利或失败条件触发时写入 ending、记录 `EndingReached` 事件并冻结时间；
`goals` 面板与 TUI 目标页共用该进度。判定不绕过 Tick，账本与状态哈希规则不变。

## 4. 模块与依赖

```
fc-world ← fc-config ← fc-sim ← 领域模块（fleet/colony/economy/science/diplomacy…）
                                   ↑
                             fc-cli / fc-runtime
fc-persist ← fc-runtime        fc-extensions ← fc-cli / fc-runtime
fc-tui ← fc-world / fc-history；fc-runtime ← fc-tui（TUI 客户端）
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
| `fc-diplomacy` | 势力采购、声望与条约、委托、冲突结算、D0–D4 目标与多路线胜利 | 已实现（M4/M23） |
| `fc-history` | 内存编年史、查询模型、导出、只读 JDBC 驱动、XML 签章、档案库镜像、PKCS#12 证据封存 | 已实现（M5+） |
| `fc-cli` | 命令解析、星图、面板与报告 | 已实现 |
| `fc-tui` | 全屏终端客户端：渲染内核、差量刷新、宽度处理、输入适配、星图/舰队/殖民地/科研/贸易/情报页面 | 已实现（M5+） |
| `fc-persist` | 存档仓库、偏好、本地化 | 已实现（M5） |
| `fc-extensions` | Mod SPI/装载器、运行时编译、宏、PNG、星图工坊、配乐工坊、JMX 注册/回环远程管理/通知与监视器、真实时间心跳、聊天室中继、fc:// 命名上下文、TLS 与证书信任、Frontier Script、XA 两阶段文件事务、货单剪贴板、HTTP、音频 | 已实现（M5+） |
| `fc-runtime` | 装配、REPL 与 `--tui` 客户端启动、存档/读档、PNG、Mod 装载、遥测与状态端点、单文件 jar | 已实现 |
| `fc-testkit` | 注解 + 反射的零依赖测试框架 | 已实现 |
| `fc-agent` | 独立诊断 Java Agent：类加载/转换观察、JMX 诊断 MBean、浅层大小 | 已实现（可选） |
| `fc-auth` | 可选 SASL FC-CHALLENGE 认证与 Kerberos/GSS 探测/上下文入口 | 已实现（可选） |
| `fc-audit` | API 覆盖审计（classfile 常量池 → 证据） | 已实现（M5） |

依赖方向：`fc-world ← fc-config ← fc-sim / 领域模块 ← fc-cli / fc-runtime`。领域模块之间不互相依赖；跨域协作通过 `WorldState` 与 `GameEvent`。`fc-persist` 只依赖 JDK；`fc-extensions` 依赖 JDK 与 `fc-world`/`fc-sim`/`fc-config`（`fc-cli` 仅为 `atlas` 制图与 `music` 配乐依赖它，无反向依赖）；`fc-tui` 只依赖 `fc-world`/`fc-history`，通过 `TuiGame` 接口与运行时解耦，`fc-runtime` 负责注入适配器；`fc-auth` 与 `fc-agent` 独立，`fc-cli` 可选依赖 `fc-auth`，Agent 通过 `-javaagent` 外部加载；`fc-audit` 独立。`fc-testkit` 只作为测试作用域依赖。`fc-audit` 位于反应堆最后，确保审计能读取全部模块产物。

## 5. 运行时装配

`fc-runtime` 的 `RuntimeSession` 完成一条固定启动链：

1. 读取 `world/default-world.xml`（或 `--world <path>`），经 XSD 与语义校验生成 `WorldDefinition`；
2. `WorldAssembler` 装配地点图、舰船、主体、技能、货物/财务账本、势力、殖民地、初始知识、船型与设施；
3. 以配置参数构造 `Economy` 与生产、科研、市场、势力、殖民地、冲突、目标各领域系统，注入 `TickPipeline.m4`；
4. 创建 `GameLoop`、`Cli`、`EventBus`，打印启动横幅；
5. 扫描工作目录 `mods/` 装载扩展（单个失败只告警）；
6. 注册 JMX `fc.telemetry:type=Game` 与动态目录 `fc.telemetry:type=Catalog`；若给出
   `--serve[=端口]`，在回环地址启动只读状态端点；
7. 若给出 `--tui`，以 `TuiGame` 适配器启动全屏指挥终端（命令仍走
   `RuntimeSession`，每条命令刷新遥测）；否则进入 REPL；
8. REPL 中 `save`/`load`/`saves`/`png` 由运行时拦截，其余命令交给 `Cli`。
9. 可选能力：`-javaagent` 加载诊断 Agent；`auth` 使用 SASL/Kerberos；
   `workbench` 使用 RowSet 离线工作台。缺失 Agent 或 KDC 时游戏仍正常运行。

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
8. **覆盖率目标**：当前审计为类型 632/3836（16.5%）、包 88/176（50.0%）、模块 20/20，Preview 违规 0。后续按 `docs/phase3/data/api-mapping.csv`
   以真实功能为载体扩张，不设数字硬阈值，也不允许无意义 API 调用。

上述条目的处置必须遵循同一流程：先写规格，再实现与测试，最后生成审计证据并本地提交。
