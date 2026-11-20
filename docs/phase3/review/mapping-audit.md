# 阶段三映射质量复核报告（2026-11-20）

> 针对用户审阅意见的正式复核。**结论影响阶段三映射表的状态分布与覆盖率**，因此本文件与 `data/api-mapping.csv`、`data/mapping-summary.md` 三者的数字必须一致（全部由 `tools/Phase3Mapper.java` 生成或据此核算）。
>
> 复核对象：`docs/phase3/data/api-mapping.csv` 的 2442 个 `Planned` 行。
> 复核依据：用户的五条质疑 —— ①没有 Pending 不等于论证过；②`fc-extensions` 712 是最大风险；③音频不应整体通过；④`java.awt.datatransfer` 的 ENV 理由不严谨；⑤`Planned` 存在仅凭包级默认批量继承的情况。

---

## 0. 先说清楚：本轮到底审了多少（可信度边界）

不做「全都审过了」的表述。本轮的实际覆盖如下：

| 范围 | 做法 | 涉及行数 |
|---|---|---|
| `fc-extensions` 全部 40 个包 | **逐类型审计**：写出该功能需要哪些类型，其余一律排除 | 712 |
| `java.sql` / `javax.sql` / `rowset` 三簇 | 逐包判定 + 运行时取证（无驱动） | 104 |
| `java.nio.channels` | 逐类型区分「文件语义」与「网络语义」 | 56 |
| 全部 `Excluded` 的 `ENV` / `DEPRECATED` | **逐条重看理由是否成立** | 265 |
| 其余约 1700 个 `Planned` | **未做逐类型审计**，仅补功能锚点并标注论证强度 | ~1700 |

因此本报告里的「43.90%」是**规划覆盖**，而其中被逐类型论证过的只有 120 行（7.13%）。这个比例不好看，但它比原来那个 63.66% 真实——这正是复核要回答的问题。

---

## 1. 结论：数字前后对照

| 项 | 复核前 | 复核后 | 变化 |
|---|---|---|---|
| 冻结清单总行数 | 3874 | 3874 | — |
| 分母（IN_DENOMINATOR） | 3836 | 3836 | **未动** |
| Planned | 2442 | **1684** | −758 |
| Pending（新增状态） | 0 | **128** | +128 |
| Excluded（分母内） | 1394 | **2024** | +630 |
| 合计 | 3836 | 3836 | ✓ |
| 类型级规划覆盖率 | 63.66% | **43.90%**（1684/3836） | −19.76pt |
| 包级（≥1 个 Planned） | 71.02%（125/176） | **48.86%**（86/176） | −22.16pt |
| 完整包 | 63.07%（111/176） | **39.20%**（69/176） | −23.87pt |
| 模块级（≥1 个 Planned） | 80%（16/20） | **35.00%**（7/20） | −45.00pt |

**一句话结论：原来那 63.66% 里，约 758 个类型（31%）经不起「这个类型在游戏里干什么」这一问。** 这不是估算，是逐包逐类型问出来的结果。

被排除的 630 行全部有原因码与一句话理由：`BIZ` 1950、`DEPRECATED` 54、`ENV` 20。

---

## 2. 逐包裁决（复核后与复核前对照）

`Planned` 列是复核后的数量。`java.lang` / `java.util` 两行不可直接比较（它们原本只有 3 / 1 个类型被划给 `fc-extensions`，下表取的是整包数），故单列说明。

| 包 | 复核前 Planned | 复核后 Planned | Pending | Excluded | 裁决依据 |
|---|---|---|---|---|---|
| `javax.management` | 72 | 30 | 0 | 43 | 进程内 `MBeanServer` + `StandardMBean` 足够；Query/DynamicMBean/权限类/远程连接器不需要 |
| `java.net` | 66 | 6 | 0 | 60 | 只留 URI/URL 与编解码；单机离线游戏不用套接字、代理、Cookie、缓存 |
| `java.nio.channels` | 56 | 17 | 0 | 39 | 只留 `FileChannel`/`FileLock`/`SeekableByteChannel` 文件语义；Socket/Datagram/Selector/Pipe/异步通道全排除 |
| `java.sql` | 55 | 0 | **55** | 0 | JDK 不带 JDBC 驱动（实测 0 个），零依赖下无法连接 → 待裁决 |
| `java.security.cert` | 52 | 0 | 0 | 52 | 不建 X.509 体系：密钥库只放对称密钥，mod 用白名单 + 摘要校验 |
| `javax.net.ssl` | 43 | 0 | 0 | 43 | 既无 TLS 服务端也无客户端 |
| `javax.naming` | 41 | 0 | 0 | 41 | 资源定位用 Path/URI/ServiceLoader，不自造目录服务（用户明确否决） |
| `javax.sound.sampled` | 34 | 25 | 0 | 9 | 只留 WAV 播放链路；`TargetDataLine`（采集）、`Port`、`ReverbType`、`AudioPermission` 排除 |
| `javax.sound.midi` | 28 | 14 | 0 | 14 | 只留默认音序器/合成器做氛围乐；设备枚举、Soundbank、SysEx、设备间转发排除 |
| `javax.management.relation` | 27 | 0 | 0 | 27 | 关系服务是为管理端建模对象关系；游戏内关系直接用领域对象 |
| `java.lang.invoke` | 25 | 7 | 0 | 18 | mod 只需 `Lookup` + `MethodHandleProxies` + `VarHandle`；字节码生成相关全排除 |
| `javax.management.openmbean` | 25 | 0 | 0 | 25 | OpenMBean/TabularData 面向通用管理控制台 |
| `javax.tools` | 24 | 0 | **24** | 0 | 玩家规则编译有潜力，但规则语言与安全边界未设计 → Pending |
| `java.net.http` | 22 | 0 | 0 | 22 | 离线可复现优先，运行期不依赖外部服务 |
| `java.lang.management` | 20 | 18 | 0 | 2 | 观测用平台 MXBean 指标；`PlatformLoggingMXBean`、`ManagementPermission` 排除 |
| `javax.sql` | 19 | 0 | **19** | 0 | 同 `java.sql`：依赖驱动 |
| `javax.management.remote` | 18 | 0 | 0 | 18 | 无远程管理需求 |
| `javax.naming.directory` | 18 | 0 | 0 | 18 | 同 `javax.naming` |
| `javax.xml.crypto.dsig` | 18 | 0 | 0 | 18 | 完整性用 HMAC-SHA256 + 摘要链，XML 签名无附加价值 |
| `java.rmi` | 17 | 0 | 0 | 20 | 无多人/远程观战需求（用户明确否决） |
| `java.rmi.server` | 17 | 0 | 0 | 27 | 同上（含 10 个已废弃桩/骨架，保留 DEPRECATED 码） |
| `javax.xml.crypto` | 15 | 0 | 0 | 15 | 同上 |
| `java.lang.constant` | 12 | 0 | 0 | 12 | 常量描述符只服务字节码生成，本项目不生成类文件 |
| `javax.management.modelmbean` | 12 | 0 | 0 | 12 | MBean 由 StandardMBean 声明，元数据不落盘 |
| `javax.naming.spi` | 12 | 0 | 0 | 12 | 自研命名 provider 无需求 |
| `javax.script` | 12 | 0 | 0 | 12 | **ENV**：JDK 21 无任何内置引擎（实测工厂数 0） |
| `javax.xml.crypto.dsig.spec` | 12 | 0 | 0 | 12 | 同 XML 签名 |
| `javax.sql.rowset` | 12 | 0 | **12** | 0 | RowSet 是 JDBC 之上的离线结果集，依赖驱动 |
| `javax.management.monitor` | 10 | 0 | 0 | 10 | 自带线程与阈值轮询，与单一 tick 时间模型冲突 |
| `javax.sql.rowset.serial` | 10 | 0 | **10** | 0 | 同上 |
| `javax.management.remote.rmi` | 9 | 0 | 0 | 9 | 同远程管理 |
| `javax.xml.crypto.dsig.keyinfo` | 8 | 0 | 0 | 8 | 同 XML 签名 |
| `javax.sql.rowset.spi` | 8 | 0 | **8** | 0 | 同上 |
| `javax.naming.event` | 7 | 0 | 0 | 7 | 目录事件监听无需求 |
| `java.nio.channels.spi` | 6 | 0 | 0 | 6 | 自研通道 provider 无需求 |
| `java.lang.instrument` | 6 | 0 | 0 | 6 | 运行时增强需极强需求；审计改静态扫描（用户明确否决） |
| `java.net.spi` | 5 | 0 | 0 | 5 | 地址解析 provider 无需求 |
| `javax.management.timer` | 3 | 0 | 0 | 3 | 引入独立时间源，与单一 Stardate 冲突 |
| `java.rmi.dgc` | 3 | 0 | 0 | 3 | 分布式 GC 只服务远程引用 |
| `javax.xml.crypto.dom` | 3 | 0 | 0 | 3 | 同 XML 签名 |
| `java.lang.runtime` | 2 | 0 | 0 | 2 | `ObjectMethods`/`SwitchBootstraps` 由编译器内部引用，源码层不出现 |
| `java.nio.file.spi` | 2 | 0 | 0 | 2 | `fcfs://` 自研文件系统属过度设计，Path/Files 已满足 |
| `javax.net` | 2 | 0 | 0 | 2 | 无套接字端点 |
| `java.rmi.registry` | 2 | 0 | 0 | 3 | 注册表即远程对象目录 |
| `javax.rmi.ssl` | 2 | 0 | 0 | 2 | RMI over SSL 无端点 |
| `javax.xml.crypto.dsig.dom` | 2 | 0 | 0 | 2 | 同 XML 签名 |
| `java.lang`（整包，非 extensions 部分） | 121（原 3 为 extensions 部分） | 121 | 0 | 3 | 仅 `ProcessHandle` 改判排除；`Process`/`ProcessBuilder` 挂 `EXPORT-OPEN`（`> export map --open`） |
| `java.util`（整包） | 132（原 1 为 extensions 部分） | 132 | 0 | 2 | 不变；`ServiceLoader` 仍是模组装载入口 |

**合计**：`fc-extensions` 计划量 **712 → 103**（运行观测 48、事件音效 25、氛围乐 14、模组装载 8、资源 URI 6、导出打开 2）。

---

## 3. 逐条回答五个复核点

### 3.1 「没有 Pending，不代表每个类型的映射都被充分论证过」——成立

原表 `Pending 0` 是**把不确定的东西强行判死**换来的，不是论证充分的证据。本轮引入 `Pending` 并实际使用 **128 行**：

| 包 | Pending | 为什么是 Pending 而不是 Excluded |
|---|---|---|
| `java.sql`（55）+ `javax.sql`（19）+ `rowset`（12+10+8） | 104 | 不是「不需要」，是**当前零依赖方案下做不到**；是允许第三方嵌入式库、还是改用 XPath/Stream 查询，需要裁决 → 待定 |
| `javax.tools` | 24 | 玩家规则编译的技术价值明确，但规则语言子集、安全边界、诊断呈现都还没有设计 → 待设计 |

`Pending` 既不计入覆盖分子、也不计入排除，因此在统计上不会被误读为「已覆盖」。

### 3.2 `fc-extensions` 712 是最大风险——成立，且是本次削减的主体

削减 609 个类型（712→103），根因不是「这些包不重要」，而是**它们此前只有一句包级理由**（`扩展与模组基础设施（工程）`），包内所有类型被整体继承。这正是「包级规则替代类型级论证」的典型。

新增机制：`rules/type-keep.tsv` —— 每个包写「这个功能需要哪几个类型」，包内其余一律按声明的理由排除；生成器断言保留的类型必须真实存在，且与类型级覆盖不冲突。

### 3.3 音频 62 个类型不应整体通过——成立，已收紧到 39

- `javax.sound.sampled` 34 → **25**：保留 WAV 播放链路（`AudioSystem`/`AudioFormat`/`AudioInputStream`/`Clip`/`SourceDataLine`/`Mixer`/`Line`/`LineEvent`/控制类）。去掉 `TargetDataLine`（麦克风采集）、`Port`/`Port.Info`（硬件端口枚举）、`ReverbType`/`EnumControl`/`CompoundControl`（合成器参数）、`AudioPermission`（只在 SecurityManager 下有意义）。
- `javax.sound.midi` 28 → **14**：氛围乐只走「默认音序器 + 默认合成器 + 程序化 Sequence」，去掉设备枚举、Soundbank 加载、SysEx、设备间转发。

音频整体降级为**可选反馈系统**：无混音器/无 MIDI 设备时静默退出，不作为启动依赖。

### 3.4 `java.awt.datatransfer` 的排除理由不严谨——成立，已按类型拆分

原先把 12 个类型统一写成「无头环境抛 HeadlessException」，逻辑上把「数据传输模型」和「系统剪贴板」混为一谈。现按类型区分：

| 类型 | 原因码 | 理由 |
|---|---|---|
| `Clipboard`、`ClipboardOwner`、`StringSelection`、`SystemFlavorMap`、`FlavorMap`、`FlavorTable`、`FlavorEvent`、`FlavorListener`（8 个） | **ENV** | 确实绑定系统剪贴板与原生格式映射，无头环境下不可用 |
| `DataFlavor`、`Transferable`、`MimeTypeParseException`、`UnsupportedFlavorException`（4 个） | **BIZ** | 纯数据传输模型，无头环境完全可用；排除判据是「没有产品需求」，不是环境限制 |

### 3.5 包级默认批量继承——确认存在，已量化

| 论证强度 | 行数 | 含义 |
|---|---|---|
| `TYPE_AUDITED` | 120（7.13%） | 逐类型给出过用途（本轮审过的 15 个包） |
| `PKG_FEATURE` | 228（13.54%） | 只有包级功能锚点，包内类型作为同一功能的组成部分 |
| `MODULE_DEFAULT` | **1336（79.33%）** | 只挂模块级锚点（核心循环），**没有包级用途论证** |

`MODULE_DEFAULT` 并不都是虚的：999 行在 `java.base`（`java.util` 131、`java.lang` 119、`java.io` 84、`java.security` 79、`java.util.concurrent` 73 …），这些包确实被核心循环整体使用。但它**不等于逐类型论证**，所以：

- 阶段四**每批开工前**必须把该批涉及的包从 `MODULE_DEFAULT` 升到 `PKG_FEATURE` 以上（写清包在该批承担的动作）；
- 明显过时或重复的类型必须改判（见 §5 候选清单）。

---

## 4. 两条硬证据（不是判断，是运行时事实）

`data/verification/runtime-capability-probe.md`（由 JDK 21.0.11 现场生成）：

```
ScriptEngineManager.getEngineFactories().size() = 0
ServiceLoader<ScriptEngineFactory>     = 0
DriverManager.getDrivers().count       = 0
ServiceLoader<Driver>                  = 0
```

- **`javax.script` 整包不可用**：Nashorn 在 JDK 15 已被移除（JEP 372），零第三方依赖下拿不到任何 `ScriptEngine`。这是 ENV，不是「我们不想用」。
- **`java.sql` / `javax.sql` / `rowset` 不可用**：JDK 从不附带 JDBC 驱动，`DriverManager` 无法建立连接。所以阶段二「只读 JDBC 查询编年史」这条设计**本身无法落地**——这才是把它们标 `Pending` 而不是 `Planned` 的原因。

---

## 5. 第二轮候选：本轮**未改判**，但应随批次裁决

这些类型现在仍是 `Planned`，但缺少强用途，属于「同类问题尚未处理完」的部分。不一次改完，是因为它们分散在核心包里，改判会牵动 M0/M2 的批次内容，应由对应批次的验收标准来定。

| 类型 | 现状 | 问题 |
|---|---|---|
| `java.util.Stack`、`Vector`、`Hashtable`、`Dictionary` | Planned | 遗留集合，与 `Deque`/`ArrayList`/`HashMap` 功能重复 |
| `java.util.Enumeration`、`StringTokenizer` | Planned | 已被 `Iterator`/`String.split`/`Scanner` 取代 |
| `java.util.Calendar`、`GregorianCalendar`、`SimpleTimeZone`、`TimeZone` | Planned | 世界时间体系是单一的 `Stardate`/`java.time`，再引入 `Calendar` 是双时间源 |
| `java.util.EventListenerProxy`、`TooManyListenersException`、`PropertyPermission` | Planned | 分别属于旧监听模型、旧事件模型与 SecurityManager 体系 |
| `java.lang.StringBuffer`、`ThreadGroup` | Planned | 前者应统一用 `StringBuilder`（并发场景用 `StringBuilder` + 锁）；后者已不推荐用于线程组织 |
| `javax.lang.model.util.SimpleElementVisitor6/7/8/9`、`ElementScanner6/7/8/9` | Planned | 版本化遗留访问器，JDK 21 统一用 `…14` 即可 |
| `javax.imageio.spi`（11）、`javax.imageio.stream`（11）、`javax.imageio.metadata`（6）、`javax.imageio.event`（5）、`plugins.tiff`（12）、`plugins.jpeg`（4）、`plugins.bmp`（1） | Planned | 若导出只调用 `ImageIO.write(img,"png",file)`，这 50 个类型一个都用不上 |
| `java.awt.image.renderable`（7）、`java.awt.color`（7） | Planned | 仅在需要独立渲染分辨率与色彩管理时才有意义 |

合计约 **70–120 行**可能还要下调。这部分我没有自行改判，原因写在上面：它属于「批次开工时的设计细化」，不属于「一眼假的包级继承」。

---

## 6. 覆盖率含义变了，需要你确认口径

复核后的 43.90% 与你批准的目标（类型 ≥63% / 包 ≥70% / 完整包 ≥62% / 模块级 ≥80%）差距不大可能靠「补论证」补回来——那 758 行里有 630 行是**确实没有游戏动作**，剩下 128 行是**确实做不到或还没设计**。

所以有三条路，需要你选一条：

**A. 接受 43.90% 为新基线（我建议这条）。** 把「全量 API」从目标降为边界：覆盖率是结果，不是任务。M5 从 704 行缩到实际能支撑的功能量，M5c 取消或并入 M6。阶段五审计时以 `Verified` 为准，届时可能还会略降。

**B. 反向补设计，把覆盖率做回去。** 为 RMI/JNDI/TLS/证书 这些被排除的方向补上真实功能（多人观战、签名 mod、目录式资源索引）。代价是这些功能要进游戏、要写、要验收，而且与你「不要为用而用」的原则直接冲突。**我不建议，但这属于你的产品判断。**

**C. 混合。** 只回补你认可的少数方向（例如签名 mod → 恢复 `java.security.cert` 52 行，或本地多人观战 → 恢复 `java.rmi` 41 行），其余按 A 处理。回补一项就把对应包改回 `Planned` 并补功能锚点，`type-keep.tsv` 改一行即可。

无论选哪条，**阶段四的批次验收标准都改为「玩家多了什么能力」，不再报告「用了多少 API 类型」**。

---

## 7. 复现

```bash
JDK=E:/jdks/ms-21.0.11/bin/java

# 规则 → 映射表 / 逐包 / 逐模块 / 统计 / 自检
$JDK -Dfile.encoding=UTF-8 tools/Phase3Mapper.java

# 运行时能力取证（脚本引擎 / JDBC 驱动 / ImageIO 格式）
$JDK -Dfile.encoding=UTF-8 tools/CapProbe.java docs/phase3/data/verification/runtime-capability-probe.md
```

规则文件（改口径只改这些，再重跑）：

| 文件 | 作用 |
|---|---|
| `rules/package-rules.tsv` | 177 包默认判定（状态 / 模块 / 系统 / 批次 / 原因码 / 场景） |
| `rules/type-keep.tsv` | **本轮新增**：按功能写「需要哪些类型」，包内其余排除 |
| `rules/type-overrides.tsv` | 类型级覆盖（含可选的 feature 列） |
| `rules/feature-anchors.tsv` | **本轮新增**：功能锚点与玩家可见验收标准 |

生成器硬断言（任一失败即非零退出）：行数与类型集合一致、`Planned/Pending` 必须有功能锚点与论证来源、`Excluded` 必须有原因码与理由、`type-keep` 的保留类型必须存在且不与类型覆盖冲突。
