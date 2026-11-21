# 测试与验收

## 原则

- 测试验证**设计契约**，不验证当前实现偶然产生的内部细节。
- 不使用第三方测试框架。`fc-testkit` 提供 `@FcTest` 注解与反射 runner；断言使用 JDK `-ea` 断言。
- 每个测试主类在任一用例失败时以退出码 1 结束；Maven `test` 阶段直接运行这些主类，失败会中断构建。
- 测试世界尽量小、显式构造，避免依赖默认世界的平衡数值；默认世界另做配置与集成验收。
- 不删除失败测试、不放宽断言、不跳过用例来换取绿色构建。

## 如何运行

在项目根目录：

```bash
# 全量编译并运行全部测试
mvn -B test

# 只运行某个模块的测试
mvn -B -pl fc-economy -am test

# 打包（同时会运行测试）
mvn -B package
```

需要 JDK 21。测试使用 Maven `exec-maven-plugin` 为每个测试主类启动独立 JVM，并显式加上 `-ea`；Surefire 不承载测试。

## 测试套件

当前共 33 个测试套件、287 个用例。

| 模块 | 测试主类 | 用例 | 覆盖 |
|---|---|---:|---|
| `fc-world` | `WorldSmokeTest` | 12 | sealed 层级、事件模式匹配、货物/资金账本、透支、利息、技能 |
| `fc-config` | `ConfigTest` | 25 | 默认世界规模、XSD 与语义校验、技能/船型/设施/科技/税率配置 |
| `fc-sim` | `M1TickTest` | 8 | 配置装配、父子层级、账本初始库存、时间与库存推进 |
| `fc-sim` | `M2SimTest` | 18 | 随机流、事件队列、进食/迁徙/告警、工作阶段、十二阶段、重放哈希、决策暂停 |
| `fc-fleet` | `VoyageTest` | 7 | 最短航路、双向、燃料扣减、启航拒绝条件、抵达 |
| `fc-colony` | `ColonyTest` | 11 | 升级、人口与消费、运输在途、补给线间隔/跳过/取消 |
| `fc-economy` | `EconomyTest` | 20 | 定价、交易守恒与部分成交、配方生产、工人独占、工资、利息、税率与精度 |
| `fc-history` | `HistoryQueryTest` | 10 | 查询过滤/分页、CSV/XML/XSD/XSLT/ZIP、XXE 拒绝、JDBC 驱动与 DataSource |
| `fc-history` | `EvidenceSignatureTest` | 5 | RSA 签章与验签、篡改/错钥拒绝、密钥 DER 往返、指纹稳定 |
| `fc-history` | `ChronicleVaultTest` | 6 | 快照与索引、幂等写入、篡改/缺失检测、排他锁、WatchService、坏索引拒绝 |
| `fc-history` | `EvidenceSealTest` | 6 | PKCS#12 往返、AES-GCM 封存/解封、错钥/篡改拒绝、输入过滤器、错误口令、参数校验 |
| `fc-runtime` | `RuntimeSmokeTest` | 26 | 默认世界启动（24 地点/27 航线）、命令、账本、哈希、导出、配乐、档案、签章、JMX、货单、三种胜利路线与三类失败条件 |
| `fc-runtime` | `M4GameplayTest` | 9 | 扫描/勘察、科研解锁、维修、借贷、船坞、外交与委托、星门终局 |
| `fc-runtime` | `PersistenceTest` | 6 | 明文重放同哈希、加密口令、损坏拒绝、槽位、查询不入日志、缺失槽位 |
| `fc-agent` | `AgentProbe` | 4 | -javaagent 注册、能力与类列表、浅层大小、释放/未知目标 |
| `fc-auth` | `AuthTest` | 6 | 机制发现、多轮认证、失败路径、Callback、资源释放、Kerberos 降级 |
| `fc-history` | `ChronicleWorkbenchTest` | 5 | 离线 CachedRowSet、过滤、排序分页、WebRowSet 版本校验、Join |
| `fc-runtime` | `SplitConsoleTest` | 5 | 令牌拒绝、完整 help/log 输出、ConsoleClient 子进程往返、远端快照、quit 停止宿主 |
| `fc-persist` | `PersistUnitTest` | 6 | 负载往返、版本拒绝、口令与篡改、双语资源包、偏好、档案名校验 |
| `fc-extensions` | `ExtensionsTest` | 9 | 内存编译、Mod 装载与签名要求、宏、PNG、JMX、HTTP、音频、描述符 |
| `fc-extensions` | `MapAtlasTest` | 6 | PNG/JPEG 元数据、逐像素确定性、滤镜、缩略图、区域读取、参数校验 |
| `fc-extensions` | `SoundtrackTest` | 5 | 同种子同曲、SMF 往返、WAV 解码、音色/元数据/控制器、参数校验与设备探测 |
| `fc-extensions` | `RemoteJmxTest` | 6 | 回环 RMI 连接、直接 RMI 客户端、多客户端、只读属性、停止后拒绝连接、端口与缺失遥测 |
| `fc-extensions` | `TelemetryMBeansTest` | 5 | 属性变化/命令通知与过滤、动态 MBean 快照、GaugeMonitor 阈值通知、Timer 心跳、QueryExp 目录筛选 |
| `fc-extensions` | `TwoPhaseFilesTest` | 5 | XA 双写提交、prepare 失败全回滚、重启恢复日志、协议错误与同 RM、单阶段提交与参数校验 |
| `fc-extensions` | `ManifestTransferTest` | 4 | 货单 flavor 与文本回退、剪贴板往返/所有权/FlavorEvent、FlavorMap 原生名与 MIME 校验、空剪贴板与不支持类型 |
| `fc-tui` | `TuiEngineTest` | 19 | 总览渲染、CLI 页面命令/选择/详情、命令桥接与不自动推进、差量刷新、ANSI 回放重建、页面无残留、回显清除、宽度/ASCII、尺寸变化、失败恢复、待办阻塞提示、tui off 返回 CLI、目标页、阻塞输入与双窗口队列停止、帮助滚动与 TUI 命令说明、列表自动滚入视口、详情滚动、output 页、尺寸监视回调 |
| `fc-extensions` | `ChatRelayTest` | 6 | 加入与广播、成员表与历史、RMI 控制面、非法输入、离开广播、关闭与端口校验 |
| `fc-extensions` | `FcNamingTest` | 6 | 绑定/查找/枚举、相对与绝对名、Reference/ObjectFactory、错误名、链接与事件、隔离与关闭 |
| `fc-extensions` | `TrustAuthorityTest` | 6 | 自签根与 CA 约束、叶子签发/用途/SAN、中间链构建、过期/异根拒绝、参数校验、密钥库往返 |
| `fc-extensions` | `TlsChatTest` | 5 | 双向 TLS 握手与匿名拒绝、TLS 消息面、密钥库文件装配、明文拒绝、关闭释放 |
| `fc-extensions` | `FcScriptTest` | 6 | ServiceLoader 发现、命令/变量、repeat、编译复用、错误语义、绑定与工厂元数据 |
| `fc-audit` | `AuditTest` | 5 | CSV、基准分母与 Preview、classfile 类型提取、报告证据、预览标记 |

`fc-science`、`fc-diplomacy`、`fc-colony` 等领域模块的玩法链由 `fc-runtime.M4GameplayTest`
从装配路径端到端验收，因此不强制为每个模块单建测试主类；模块内部规则仍应尽量就近测试。

`fc-audit` 在 `test` 阶段还会运行一次全量审计（`fc.audit.AuditMain`），扫描全部模块的
`target/classes` 并生成 `target/api-usage/` 证据；发现 Preview class 或 Preview 类型引用时构建失败。

## 关键验收维度

### 确定性

同一世界种子与同一操作序列必须产生相同状态哈希。测试使用两个独立世界实例运行相同天数，比较最终 `StateHasher` 摘要与事件序列。

状态哈希覆盖：星历、货物总量、各地点库存、主体状态与行程、势力财政与声望、关系矩阵、殖民地、在途运输、补给线、待决决策、风险修正、价格冲击、生产中断、工作单元、账户余额与债务。编年史属于历史记录而非当前状态，不进入哈希。

### 守恒

每类货物在所有分区（地点 + 货舱 + 在途）中的总量必须守恒；信用点在所有账户中的总量必须守恒。测试在交易、生产、运输、工资、利息之后调用 `verifyLedger()`。

### 状态转换

航行、迁徙、工作单元、补给线、决策都有明确的生命周期。测试覆盖每个阶段的进入条件、推进与终止，尤其是：

- 迁徒当天不改变地点；
- 补给线首班在次日后发车；
- 待决决策存在时时间不推进；
- 工作单元完工只产出一次并从列表移除。

### 边界与异常

- 燃料不足、库存不足、资金不足、无工人、生产中断、非法配置、未知 id。
- 负数天数、负数资源、`credits` 混入货物、未知角色/势力/资源、重复 id。
- 配置错误必须在启动/加载时失败，而不是运行到一半才抛异常。

### 持久化

- 同种子、同命令序列保存后读档，`StateHasher` 必须一致；明文与加密路径分别验证。
- 错误口令、篡改密文、损坏的校验和、未来格式版本必须被拒绝，且不替换当前会话。
- 槽位名校验必须阻止路径穿越；`saves` 按字母序返回。
- `save`/`load`/`hash`、`query`、`export`、`atlas`、`music`、`vault`、`seal`、`unseal`、`sign`、`verify`、`jmx`、`chat`、`res`、`trust`、`script`、`manifest` 不进入重放日志。

### 编年史查询与导出

- 查询条件、排序与分页必须稳定；同一编年史多次查询结果一致。
- CSV/XML 必须包含表头与完整字段；XML 通过内置 XSD 校验，HTML 由内置 XSLT 生成，
  ZIP 证据包固定包含 `chronicle.xml`、`chronicle.csv`、`chronicle.html`、`manifest.properties`。
- XML 解析必须拒绝 DOCTYPE 与外部实体（XXE），样式表转换关闭外部访问。
- 只读 JDBC 驱动必须支持 `DriverManager`、`DataSource`、`PreparedStatement`、`LIKE`、
  `COUNT(*)` 与结果集元数据，并拒绝写入/DDL；未绑定会话返回 `SQLException`。

### 证据封存

- 同一密钥封存/解封必须字节级还原；两次封存因随机 IV 不同而不相同。
- 换用另一把密钥、篡改密文、口令错误、魔数不符必须失败，且不得输出任何明文。
- 封存文件中的序列化流只能含白名单类型；其他对象必须被 `ObjectInputFilter` 拒绝。
- 密钥库首次生成后重载，密钥指纹不变；错误口令在加载阶段即失败。

### 档案签章

- 签名产物必须能被内嵌 `KeyValue` 验签，并能还原原始负载字节。
- 篡改负载、使用另一把公钥、伪造或缺失文件必须验签失败。
- 公钥（X.509）与私钥（PKCS#8）DER 往返后仍可签可验，公钥指纹稳定。
- 首次调用生成密钥并落盘，第二次调用复用同一密钥。

### 档案库

- 同一编年史重复写入必须复用同一快照，索引不重复追加；编年史增长后产生新快照并保持顺序。
- 索引读取必须拒绝坏行、坏 CRC 与非法 UTF-8；`verify` 必须发现字节数变化、摘要变化与文件缺失。
- 锁被占用时必须拒绝写入；释放后可继续写入。
- `watch` 必须观察到新快照（过滤索引、锁与临时文件），超时返回空列表而不是抛异常。

### 回环 JMX 管理端点

- 端点 URL 必须形如 `service:jmx:rmi:///jndi/rmi://127.0.0.1:<端口>/jmxrmi`，端口 0 自动分配，
  且只能从回环地址连接；同一端口重复启动必须失败。
- `GameTelemetry` 注册后，远程快照读取的八个属性必须与会话状态一致；未注册时必须明确失败。
- 只读 MBean 的属性写入按 JMX 语义返回空列表，原值不变；不得使用异常或忽略错误来伪装成功。
- `stop` 必须停止连接器并注销注册表，之后连接被拒绝；重复 `close()` 幂等。
- 端口越界在启动前拒绝；`jmx` 命令不进入重放日志。

### JMX 通知、动态 MBean 与心跳

- `GameTelemetry` 必须实现 `NotificationEmitter`：`StateHash` 变化广播
  `AttributeChangeNotification`（old/new 值正确），命令计数变化广播
  `fc.telemetry.command`，`userData` 含 `commandCount` 与 `day`；计数未变化不得重复广播，
  按类型过滤必须生效。
- `FrontierCatalog` 是 `DynamicMBean`：`getMBeanInfo` 自描述 3 个只读属性与 2 个操作；
  `snapshot` 返回 `CompositeData`，`find` 按域枚举；属性写入必须抛 `AttributeNotFoundException`，
  `getAttributes` 跳过缺失项。
- `GaugeMonitor` 观测 `Ships`：越过 high 阈值必须发出 `jmx.monitor.gauge.high`，
  `derivedGauge` 反映当前值；阈值类型必须与被观测属性一致（整型属性用整型阈值）。
- `Heartbeat` 由 `Timer` MBean 驱动，`fc.heartbeat` 通知按周期送达；`beats()` 由内部
  监听器计数（`getNbOccurences` 是配置次数，不是已发送次数），`close()` 后 Timer 注销。
- `QueryExp` 用字符串 `Query.match`（`?*`）筛选 `StateHash` 非空的 MBean；
  数值 `Query.gt` 不得用于字符串属性。
- `jmx beans` 与 `jmx heartbeat` 不进入重放日志；心跳只消耗真实时间，不推进游戏 Tick。

### XA 两阶段文件事务

- `export <格式> <路径A> <路径B>` 必须在两阶段提交成功后才发布两份文件；
  任一分支 prepare 失败时两个目标都不得出现，禁止“只写了一半”的导出。
- 每个分支都走 `XAResource` 协议：`start`/`end`/`prepare`/`commit`；
  prepare 只写同目录临时文件与 `.fcxa-*.xa` 恢复日志，commit 才原子替换目标。
- 恢复日志必须记录分支 `Xid` 与临时文件名；进程重启后用新资源实例
  `recover` 能找回未决分支并完成 commit 或 rollback。
- 未 `start` 就 `end`、重复 `rollback`、未知分支 `commit` 必须以明确的
  `XAException` 错误码（`XAER_PROTO`/`XAER_NOTA`）拒绝，不得静默成功。
- 同一事务重复目标、空事务必须拒绝；完成后不得残留日志或临时文件；
  `export` 不进入重放日志。

### 货单剪贴板（Transferable）

- 货单必须同时支持自定义 `application/x-fc-manifest` flavor 与
  `DataFlavor.stringFlavor`；不支持的 flavor 抛 `UnsupportedFlavorException`。
- `manifest copy` 的内容要能被 `manifest paste` 原样取回；`manifest text` 使用
  `StringSelection`，只认文本的接收方也能粘贴。
- 进程内剪贴板必须在内容变化时通知 `ClipboardOwner.lostOwnership` 与
  `FlavorListener.flavorsChanged`；`Clipboard` 基类不派发这些事件，需由
  `LocalClipboard` 补齐。
- 自定义 flavor 必须登记到 `SystemFlavorMap`（原生名 `FC_MANIFEST`），
  `FlavorTable` 能双向查询；非法 MIME 以 `MimeTypeParseException` 拒绝。
- `manifest` 命令只操纵进程内状态，不进入重放日志；无图形环境时必须可用。

### 星际远征指挥终端（TUI）与双窗口模式

- `--tui` 单窗口模式：TUI 接收命令，底部一行显示最后反馈；每次提交后全量重绘，
  行输入回显不得残留，页面切换不得叠加上一页内容。
- `--tui --console`（或 `--split`）：本窗口是可交互 TUI，独立 CLI 窗口通过
  回环地址 + 随机令牌连接；两个窗口都能提交命令并操作同一世界。
  独立 CLI 窗口的 `help`/`log all` 等完整输出不得截断；TUI 页面用于可视化，
  也可用 `output` 页回看完整命令输出。任一窗口的页面命令
  （map/ships/colonies/research/market/log/status/decisions/goals/help）
  必须让 TUI 刷新并切换页面；`quit` 必须同时结束 CLI 与 TUI。
- 长内容必须可达：`help`、`goals`、市场/科研报告和 `output` 页可用
  `scroll up/down [N]`、`scroll top/bottom`、`page up/down` 浏览；
  标题显示可见范围。列表 `select` 必须把选中项自动滚入视口，
  `inspect` 详情弹窗也必须可滚动。超宽表格单元格用 `..` 提示，
  完整内容进入详情或 `output`。
- 终端尺寸变化必须自动重排：后台探测窗口行列，变化后重新布局并全量重绘；
  `FC_TUI_COLS/ROWS` 覆盖仍在每次输入后立即生效。
- 输入必须走阻塞行读取。禁止用 `Reader.ready()`/`InputStream.available()`
  轮询决定是否读命令：Windows 控制台可能始终返回不可读，导致 TUI 无法输入。
  双窗口 TUI 由守护输入线程 + 100 ms 队列轮询驱动，外部 `quit` 后不得再等一次回车。
- Windows 新窗口必须在启动前切到 UTF-8（`chcp 65001`）并以 UTF-8 读写，
  中文帮助/事件/提示不得出现乱码。
- 渲染只消费 `Snapshot`：重复渲染不得执行命令或推进 Tick；只有 CLI 的
  `next <天数>` 才改变世界。快照构建与命令执行必须串行化。
- 待决事项出现时 `next` 必须停止推进：TUI 状态行与情报页必须说明“规则阻塞”，
  给出决策 id、选项 id 与 `decisions`/`decide <id> <选项>` 处理方法；
  CLI 窗口同时收到完整原因。不得只表现为无反馈。
- 列表操作为 `select <N|up|down>`、`inspect`、`back`；滚动为
  `scroll up|down [N]`、`scroll top|bottom`、`page up|down`。
  中文/emoji 按显示宽度计算，数据中的控制字符必须清洗；
  `FC_TUI_COLS/ROWS` 可覆盖尺寸。

### fc:// 命名上下文

- 名称必须是 `fc:<scope>/<path>`，scope 只能是 `mod`/`config`/`save`/`service`/`chronicle`；
  空段、`.`、`..` 与未知 scope 在解析期拒绝，不得落到绑定表。
- 相对名相对当前子上下文，绝对名（以 `fc:` 开头）从根解析；两者指向同一绑定时必须相等。
- `bind`/`rebind`/`unbind`/`rename`/`createSubcontext`/`destroySubcontext` 的异常语义
  必须使用 JNDI 家族，不得用通用运行时异常代替。
- `registerService` 绑定 `Reference`，`lookup` 经 `NamingManager`/`ObjectFactory` 重建对象；
  `listBindings` 与 `services` 的结果必须可按名字枚举。
- 绑定、解绑与重绑触发 `NamingEvent`；`LinkRef` 必须由 `lookupLink` 解引用。
- 命名空间互相隔离；关闭后继续访问必须失败；`res` 命令不进入重放日志。

### TLS 加密信道与证书信任

- 证书链必须是「叶子 → 中间（可选）→ 自签根」：叶子用签发者公钥验签成功，
  根证书 `basicConstraints` 为 CA，叶子不是 CA 且带对应的扩展密钥用途。
- 链校验只使用 PKIX + 显式信任锚；吊销检查关闭，语义是「自签 + 白名单」，
  不得宣称具备在线吊销能力。
- 过期、异根、缺失中间证书的链必须被 PKIX 拒绝；错误类型为
  `CertPathValidatorException` / `CertPathBuilderException`，不得用通用异常替代。
- 密钥库必须是 PKCS#12：私钥条目带完整链，信任库只放证书；
  写盘后读回必须得到相同指纹与可用的私钥。
- `chat open <端口> tls <目录>` 的消息面必须启用 TLS 并强制客户端证书；
  无证书的匿名客户端握手失败，且中继在拒绝后仍可服务。
- `trust init` 不得覆盖已有信任库；`trust status` 必须完成有效期与 PKIX 校验；
  `trust` 与 `chat` 命令不进入重放日志。

### Frontier Script

- 引擎必须能通过 `ScriptEngineManager` 按名 `frontier`/`fcs` 被发现，
  元数据（名称、扩展名、MIME）与 `getParameter` 一致。
- `set`/`echo`/`repeat` 与 `${变量}` 只影响脚本自身；未知变量、非法次数、
  缺宿主都以 `ScriptException` 报告，并带行号。
- `compile` 在编译期拒绝语法错误，`CompiledScript` 可重复求值；
  变量替换发生在求值期。
- CLI 执行脚本时，展开的每条命令都必须先进入重放记录再执行；
  脚本文件本身与 `script` 命令不得进入存档，否则读档会缺命令或依赖已删除的文件。
- 宿主抛出的异常应转成带行号的脚本错误，不得打断 REPL。

### 聊天室中继

- 只绑定回环地址；客户端必须先 `NICK` 再 `MSG`，昵称重复（大小写不敏感）与超长均被拒绝。
- 两条客户端连接互相可见：加入广播 `JOIN`，离开广播 `PART`，消息广播带全局递增序号。
- `WHO` 返回稳定顺序的成员表，`HIST n` 返回按时间正序的最近 n 条；系统广播 `id` 为 0。
- RMI 控制面的成员表与广播结果必须与 TCP 面一致；关闭后新连接被拒绝，关闭可重复调用。
- `chat` 命令不进入重放日志。

### 配乐工坊

- 同一世界状态（种子）必须生成逐事件相同的 MIDI；不同种子应得到不同旋律。
- SMF type 1 落盘后读回，轨道数、分辨率与音符数一致；元事件（曲名、速度、拍号）与
  程序变更/控制器确实写入。
- WAV 必须是 44.1 kHz/16-bit/单声道且非全静音；渲染不依赖声卡。
- 非法格式、空序列与不可用设备必须被拒绝或优雅降级，不得抛未捕获异常。

### 扩展与外围能力

- 运行时编译必须真实装载并执行用户源码；编译错误包含诊断。
- Mod 装载必须校验 id、语义化版本、API 版本与重复 id；开启签名要求时拒绝未签名 JAR。
- 宏必须拒绝缺失参数与含换行的参数；PNG 输出必须确定性（同输入同字节）。
- JMX、HTTP 与音频在缺少设备/端口时优雅降级，不使启动失败。
- 星图工坊必须逐像素确定；PNG tEXt 与 JPEG COM 元数据可读回；滤镜确实改变像素；
  缩略图与区域读取返回请求的尺寸；参数越界与未知格式被拒绝。

### 诊断 Agent、认证与离线工作台

- `-javaagent` 未加载时 `diagnostics` 必须明确提示，游戏照常运行；加载后 MBean
  `fc.agent:type=Diagnostics` 可读，类列表排序受 limit 限制，浅层大小不是完整内存。
- SASL 机制发现必须反映真实 Provider 集合；`auth sasl` 成功/错误口令/未知用户/
  授权不匹配路径必须明确；Kerberos 无 realm/KDC/keytab 时只报告未就绪，
  不得伪造登录成功。
- `workbench` 的 `CachedRowSet` 在连接关闭后仍可分页/过滤/排序；`WebRowSet`
  导出带 `schema_version=fc-workbench/1`，非法 XML/缺列/不兼容版本必须拒绝；
  `JoinRowSet` 能连接事件与地点目录；命令不进入重放日志。

### 世界规模与多路线胜利

- 默认世界必须包含 24 个地点、27 条航线，新增外缘星区与原环线相连且可探索；
  `ConfigTest` 锁定该规模。
- 任一胜利路线达成后必须设置 ending、写入终局事件并冻结时间：
  - 商业霸权：信用点 ≥ 10000；
  - 外交联盟：任一势力声望 ≥ 60；
  - 星域测绘：全部地点达到 CHARTED；
  - 工程终局：星门或信标工程完成（原有路线）。
- 失败条件必须优先于胜利判定，任一触发也必须设置 ending、写入 `EndingReached` 并冻结时间：
  - 船员危机：≥ 6 名 CREW 处于饥饿/疲劳/士气低落；
  - 债务违约：玩家债务 ≥ 5000；
  - 声望崩溃：任一势力声望 ≤ -50。
- `goals` 面板必须同时展示 D0–D4、V1–V3、F1–F3 进度与阈值；TUI `goals` 页渲染同一面板。
- 多窗口/`tui on` 时，宿主通知 + 100 ms 后台刷新必须自动反映其他窗口的命令；
  不得只靠手动重进，也不得因读取输入而阻塞界面刷新。

### 审计

- 基准分母只含 `IN_DENOMINATOR` 类型；Preview 不计入分母。
- 类型使用必须来自 class 常量池/描述符/签名，而不是源码 import 或测试代码。
- 报告必须能回答「哪个类型被哪个 class 使用」；存在 Preview 违规时退出码为 1。

## 当前限制

- 测试不是 JUnit 测试；IDE 需要把测试主类作为普通 Java 程序运行，或依赖 Maven `test`。
- 未实现自动包扫描；新增测试必须加入对应测试主类的 `TestRunner.run(...)` 调用。
- `StateHasher` 覆盖当前持久状态但不覆盖编年史；存档一致性只保证重放后的世界状态一致，不保证历史文本。
- 存档采用重放路线，因此命令日志是格式的一部分；没有旧格式迁移器。
- 审计只读取 classfile 常量池与描述符；泛型擦除后的真实类型参数、反射字符串与运行时生成的类不计入，
  结果偏向保守（可能低估，不会虚报）。
- 未覆盖：CLI 交互脚本、长局平衡性、并发压力、真实多机网络与 Mod 沙箱。它们属于后续里程碑。
