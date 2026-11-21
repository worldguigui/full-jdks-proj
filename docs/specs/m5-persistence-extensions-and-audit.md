# M5 持久化、扩展与审计

> 状态：已实现（v0.5.0，2026-11-21）。本文是 M5 的实施依据与落地记录。

## 1. 目标

M4 让世界有完整的长期因果链，但一局游戏仍只活在进程内。M5 解决四件事：

1. **可恢复**：把一局游戏写成可校验、可选加密的单文件存档，并在读档后证明状态逐字节一致；
2. **可扩展**：为外部 Mod、运行时编译、宏脚本提供受控入口，而不是修改核心代码；
3. **可观察/可导出**：通过 JMX、回环 HTTP 与 PNG 星图观察运行中的世界；
4. **可审计**：用编译产物而不是口头声明证明 Java SE API 的真实使用情况。

M5 不改变 M4 已确定的 Tick、RNG 与账本不变量；所有新增能力围绕既有世界状态工作。

## 2. 持久化

### 2.1 路线：种子 + 操作日志 + 状态哈希

存档不序列化 `WorldState` 对象图，而是保存：

- 初始世界来源（`resource:world/default-world.xml` 或 `file:<绝对路径>`）；
- 存档时的星历与 `StateHasher` 摘要；
- 按顺序记录的全部**可重放命令**。

读档时从同一世界来源重新装配、按序重放命令，再比较 `StateHasher.hash` 与存档摘要。
不一致即视为失败，不提供「尽力恢复」。这样避免把 sealed 领域模型、账本内部结构与
事件实现细节固化进磁盘格式；世界规则变化时由格式版本显式拒绝，而不是静默读出半个世界。

以下命令不进入重放日志：`save`、`load`、`saves`、`hash`、`help`、`quit`、`exit`；
后续加入的 `query`、`export`、`atlas`（别名 `chart`）、`music`（别名 `soundtrack`）、`vault`（别名 `archive`）、`seal`、`unseal`、`sign`、`verify`、`jmx`、`chat`、`res`、`trust`、`script`、`manifest` 同样排除
（`export`/`atlas`/`music`/`vault`/`seal`/`unseal`/`sign` 会写文件，`watch` 还会阻塞，读档时不得重复执行）。
其余纯查询命令仍会记录，这是格式的既定语义。
其余命令按输入顺序原样记录；纯查询命令虽不改变状态，但保留在日志中不影响重放结果，
也便于复现当时的查看行为。`RuntimeSession.execute` 是唯一记录入口；直接调用
`cli().execute` 只用于诊断，不进入存档。

### 2.2 文件格式

`.fcsave` 是单文件 JAR：

| 条目 | 内容 |
|---|---|
| `META-INF/MANIFEST.MF` | `Manifest-Version`、`Created-By` |
| `save.properties` | `format`、`magic`、`checksum`、`day`、`world`、`commandCount`；加密时另有 `encrypted`、`salt`、`iv`、`iterations` |
| `game.bin` | 明文负载或 AES-GCM 密文 |

负载二进制字段依次为：格式版本、魔数 `FCSAVE`、世界来源、状态哈希、星历、命令条数、逐条 UTF 命令。
读取时要求没有尾部多余数据，命令条数有上限。

### 2.3 完整性与机密性

- 明文存档始终带负载的 SHA-256；读取时用 `MessageDigest.isEqual` 做常量时间比较。
- 带口令时使用 `PBKDF2WithHmacSHA256`（120,000 次迭代、16 字节盐）派生 256 位密钥，
  `AES/GCM/NoPadding`（96 位 IV、128 位认证标签）同时提供机密性与完整性；口令数组用后清零。
- 槽位名限定 `[A-Za-z0-9_-]{1,32}`，装配路径前校验，杜绝路径穿越。
- 读取时显式读完 JAR 全部条目，触发 `JarFile(..., true)` 的签名验证。
- 写入先落到同目录临时文件，`FileChannel.force(true)` 后以原子 move 替换；无原子 move 时降级为普通替换。

### 2.4 失败语义

`SaveException` 区分：存档不存在、不是本游戏存档、版本不兼容、缺少口令、口令错误/密文损坏、
校验和不一致、JAR 损坏。所有失败都不会替换当前运行中的会话。

## 3. 偏好与本地化

- `PreferenceStore` 位于 `java.util.prefs` 的 `/fc/frontier-chronicle/<profile>`，档案名与键均校验；
  支持字符串、整数、布尔值，键按字母序返回；`flush()` 失败返回 `false` 而不是抛异常。
- `Localization` 使用 `ResourceBundle` + UTF-8 资源包与 `MessageFormat`；
  自定义 `ResourceBundle.Control` 关闭默认 Locale 回退，避免「请求英文却得到中文」。
  缺失键返回 `!key!`，`has()` 可预检。
- 资源包：`messages.properties`（英文基础包）与 `messages_zh_CN.properties`。

## 4. 扩展系统

### 4.1 Mod SPI

```java
public interface FrontierMod {
    int API_VERSION = 1;
    void activate(ModContext context) throws Exception;
    default void deactivate() {}
}
```

`ModContext` 提供 Mod 目录、`setting.*` 配置与 JDK `Logger`。Mod 以 JAR 或包含
`mod.properties` 的目录分发；描述符字段：`id`、`name`、`version`、`mainClass`、
`apiVersion`，可选 `setting.*`、`requireSignature`。

装载规则：

- `id` 必须匹配 `[a-z][a-z0-9._-]{1,63}` 且全局唯一；
- `version` 为语义化版本格式；`apiVersion` 必须等于当前 SPI 版本；
- 主类必须实现 `FrontierMod` 并提供无参构造；
- 父加载器为应用类加载器，Mod 之间不共享私有类；
- 开启签名要求时，JAR 必须具备统一的代码签名者，否则拒绝；
- 单个 Mod 失败只记录错误，不阻止其他 Mod 装载；关闭时逆序调用 `deactivate()` 并关闭加载器。

主程序启动时扫描工作目录 `mods/`，默认不强制签名。

### 4.2 运行时编译

`ModCompiler` 基于 JSR-199 `ToolProvider.getSystemJavaCompiler()`：

- `compile(className, source)` 在内存中编译并返回 `Class`；
- `compileTo(dir, className, source)` 写出 `.class`，遵循 `-d` 与 `java.class.path`；
- 固定 `-proc:none`、UTF-8 与当前 `java.class.path`，不显式启用预览；语言级别跟随运行 JDK
  （项目要求 JDK 21，且 JSR-199 编译不传 `--enable-preview`）；
- 编译失败抛出 `ModException`，附带 `DiagnosticCollector` 格式化后的诊断。

### 4.3 宏

`Macro` 解析 `#` 注释、`@name:`/`name:` 元数据与 `${param}` 占位符；支持保存/读取文本、
参数展开、按顺序执行并收集每条命令输出。缺失参数与含换行的参数被拒绝。

### 4.4 观察与导出

| 能力 | 实现 | 行为 |
|---|---|---|
| JMX | `GameTelemetry` / `GameTelemetryMBean` | 平台 MBeanServer 注册 `fc.telemetry:type=Game`，暴露星历、哈希、地点/舰船数、信用点、终局、命令数；每次命令后刷新；实现 `NotificationEmitter`，状态哈希变化广播属性变更、命令计数变化广播 `fc.telemetry.command` |
| 动态目录 | `FrontierCatalog` | `DynamicMBean` 自描述属性/操作；`snapshot` 返回 `CompositeData`，`find` 按域枚举 |
| 监视器 | `TelemetryMonitor` | 注册 `GaugeMonitor`，按阈值广播 `jmx.monitor.gauge.high/low`，100 ms 采样 |
| 心跳 | `Heartbeat` | `Timer` MBean 按真实时间广播 `fc.heartbeat`；内部监听器统计触发次数，不推进 Tick |
| XA 双写 | `TwoPhaseFiles` / `FileXaResource` / `FcXid` | 自研 `XAResource` 文件分支：prepare 暂存 + 恢复日志，commit 原子发布；`export` 两个路径时全成或全不成 |
| 货单剪贴板 | `CargoManifest` / `LocalClipboard` / `ManifestTransfer` | `Transferable` 货单 + 进程内 `Clipboard`；所有权通知、`FlavorEvent`、`SystemFlavorMap` 原生名与 MIME 校验 |
| 指挥终端 | `fc-tui`：`TuiApp` / `ScreenBuffer` / `DiffRenderer` / `TextWidth` | 全屏 ANSI 客户端：舰桥总览 + 六个工作区、详情弹窗、CONSOLE 命令输出；直接复用 CLI 页面命令、行输入 + ASCII 降级，渲染不推进世界 |
| 回环 JMX | `RemoteJmx` | 本机 RMI 注册表 + `RMIConnectorServer`，只监听回环；`start/status/stop/beans` 与 `QueryExp` 筛选；只读属性写入按 JMX 语义返回空列表 |
| 聊天室中继 | `ChatRelay` | 回环 TCP 行协议（NICK/MSG/WHO/HIST/PING/QUIT）+ 虚拟线程；RMI 控制面 `fc-chat-control` 暴露成员表与广播 |
| 脚本 | `FcScriptEngine` / `FcScriptEngineFactory` | 自研 `javax.script` 引擎（ServiceLoader 注册）：`set`/`echo`/`repeat`、变量替换、预编译；脚本展开的命令逐条进入重放日志 |
| 信任 | `TrustAuthority` / `SecureChannel` / `CertificateChains` | 自签根与中间 CA、叶子证书签发、PKIX 链构建/校验、PKCS#12 密钥库；聊天中继可启用 TLS 双向认证 |
| 命名 | `FcNaming` | `fc:<scope>/<path>` 进程内 JNDI 上下文；自研 InitialContextFactory，Reference/ObjectFactory 重建服务，支持枚举、事件与链接 |
| HTTP | `StatusServer` | 仅绑定回环地址，`GET /status` 返回 UTF-8 JSON；`fetch` 用 `java.net.http` 读取并设超时 |
| PNG | `StarMapRenderer` | 确定性的圆形布局、按 sealed 地点类型着色、航线、舰船与标签；`ImageIO` 写出，兼容 headless |
| 音频 | `SoundBank` / `Soundtrack` | 提示音合成；按世界状态确定性生成 MIDI 并可离线渲染 WAV；无音频设备时优雅降级 |
| 档案库 | `ChronicleVault` | 内容寻址 ZIP 快照、锁保护的索引、SHA-256 校验、`WatchService` 变更监视 |
| 封存 | `EvidenceSeal` | PKCS#12 密钥库、AES-256-GCM 封存、白名单反序列化、原子落盘 |

启动参数：`--serve` 或 `--serve=<端口>` 启动状态端点；`png [路径]` 导出星图。

## 5. CLI 与运行时会话

新增启动器命令（由 `fc-runtime` 处理，不属于 `Cli.COMMANDS`）：

```text
save <槽位> [口令]   # 写存档，成功后提示读档命令
load <槽位> [口令]   # 重放并核验哈希；失败不替换当前会话
saves                # 列出存档目录中的槽位
png [路径]           # 导出 PNG 星图，默认 starmap.png
```

读档成功后重建 `RuntimeSession`，刷新 JMX 遥测与状态端点引用；旧会话不再被使用。

## 6. API 覆盖审计

### 6.1 输入与口径

- 基准：阶段一冻结清单 `docs/phase1/data/coverage-baseline.csv`，只统计
  `coverage_scope=IN_DENOMINATOR` 的 Java SE 正式类型；Preview 不计入分母。
- 扫描对象：各模块 `target/classes` 下的 class 文件常量池，而不是源码 import 或注解声明。
- 引用识别：`CONSTANT_Class` 与字段/方法描述符、签名中的 `L...;` 类型；
  内部类名归一为二进制名。

### 6.2 输出与退出码

- `docs/audit/api-usage-report.md`：总览、按模块覆盖、Preview 违规、未覆盖包前 20、按模块类型清单；
- `docs/audit/api-usage.csv`：逐类型 `USED`/`UNUSED`/`EXCLUDED`、引用次数与被引用 class（最多 16 个）；
- 存在 Preview class 或引用 `EXCLUDED_PREVIEW` 类型时退出码为 1；覆盖率本身不设硬阈值，
  避免为数字制造无意义调用。

审计在 Maven `test` 阶段自动运行；也可单独执行：

```bash
mvn -B -pl fc-audit -am test
java -cp fc-audit/target/classes fc.audit.AuditMain --out docs/audit
```

### 6.3 当前基线（v0.5.0）

- 类型 256 / 3836（6.7%），包 48 / 176（27.3%），模块 8 / 20，扫描 267 个 class；
- Preview 违规 0。

该数字是诚实基线，不代表最终目标。后续批次按 `docs/phase3/data/api-mapping.csv`
引入 XML/查询、JDBC 编年史、脚本、远程管理、证书与签名、图像/打印、网络等承载功能，
每次扩展都必须同时给出真实功能、测试与审计增量。

## 7. 模块边界

| 模块 | M5 职责 |
|---|---|
| `fc-persist` | 存档仓库、负载、异常、偏好、本地化；只依赖 JDK |
| `fc-extensions` | Mod SPI/装载器、运行时编译、宏、PNG、JMX、HTTP、音频；只依赖 JDK 与 `fc-world` |
| `fc-audit` | 基准读取、classfile 扫描、报告生成；审计阶段置于反应堆末尾 |
| `fc-runtime` | 启动参数、`mods/` 装载、存档/读档/PNG 命令、JMX 与端点生命周期 |
| `fc-cli` | 仅补充 help 中的启动器命令说明；游戏命令仍是唯一清单 |
| `fc-tui` | 独立全屏终端客户端；只依赖 `fc-world`/`fc-history`，通过 `TuiGame` 接入运行时 |

`fc-audit` 移到反应堆最后，使完整审计能看到所有模块的 `target/classes`。

## 8. 验收与测试

| 套件 | 用例 | 覆盖 |
|---|---:|---|
| `fc-persist` `PersistUnitTest` | 6 | 负载往返、未来版本拒绝、口令与篡改、英文/中文资源包、偏好持久化与删除、档案名校验 |
| `fc-extensions` `RemoteJmxTest` | 6 | 回环连接与遥测读取、直接 RMI 客户端、多客户端、只读属性、停止后拒绝连接、端口校验与缺失遥测 |
| `fc-extensions` `TelemetryMBeansTest` | 5 | 属性/命令通知与过滤、动态 MBean 快照、GaugeMonitor 阈值通知、Timer 心跳、QueryExp 目录筛选 |
| `fc-extensions` `TwoPhaseFilesTest` | 5 | XA 双写提交、prepare 失败全回滚、重启恢复日志、协议错误与同 RM、单阶段提交与参数校验 |
| `fc-extensions` `ManifestTransferTest` | 4 | 货单 flavor/文本回退、剪贴板所有权与 FlavorEvent、FlavorMap 原生名与 MIME 校验、空剪贴板 |
| `fc-tui` `TuiEngineTest` | 11 | 总览渲染、CLI 页面/详情、命令桥接、差量刷新、ANSI 重建、页面无残留、回显清除、宽度/ASCII、尺寸变化、失败恢复、待办阻塞提示 |
| `fc-extensions` `FcNamingTest` | 6 | 绑定/查找/枚举、相对与绝对名、Reference/ObjectFactory、错误名、链接与事件、隔离与关闭 |
| `fc-extensions` `TrustAuthorityTest` | 6 | 自签根与 CA 约束、叶子签发/用途/SAN、中间链构建、过期/异根拒绝、参数校验、密钥库往返 |
| `fc-extensions` `TlsChatTest` | 5 | 双向 TLS 握手与匿名拒绝、TLS 消息面、密钥库文件装配、明文拒绝、关闭释放 |
| `fc-extensions` `FcScriptTest` | 6 | ServiceLoader 发现、命令/变量、repeat、编译复用、错误语义、绑定与工厂元数据 |
| `fc-extensions` `ChatRelayTest` | 6 | 加入与广播、成员表与历史、RMI 控制面、非法输入、离开广播、关闭与端口校验 |
| `fc-extensions` `ExtensionsTest` | 9 | 内存编译、目录 Mod 装载、未签名拒绝、宏展开/保存/执行、确定性 PNG、JMX 注册与更新、HTTP 端点与 JSON、音频能力探测、描述符校验 |
| `fc-runtime` `PersistenceTest` | 6 | 明文重放同哈希、加密口令、校验和损坏拒绝、槽位列表、查询不入日志、缺失槽位 |
| `fc-audit` `AuditTest` | 5 | CSV 引号、基准分母与 Preview、常量池类型提取、覆盖率与证据、Preview class 标记 |

全部测试通过；存档、Mod、审计测试使用临时目录，不污染工作区。

## 9. 明确不在 M5

- 存档云同步、跨版本迁移器与存档编辑器；
- 远程 Mod 仓库、依赖解析与沙箱权限模型；
- 多人联机与网络传输协议；
- GUI 编辑器；
- 为提升覆盖率而引入的无功能 API 调用。

上述能力如需引入，必须按「真实功能 → 测试 → 审计增量」的顺序单独立项。
