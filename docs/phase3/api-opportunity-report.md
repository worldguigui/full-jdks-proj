# 阶段三：API 能力机会盘点报告

> 项目目标已变更为 **B（API 覆盖优先，游戏作为承载载体）**，见 `project-direction.md`。
> 本报告对 14 个曾被排除或悬置的 API 方向逐一盘点，每方向按九项给出：能力说明 / 候选功能 / 涉及类型 / 功能架构 / 运行环境要求 / 第三方 Provider 需求 / 验收标准 / 成本与风险 / 准入建议。
> **不得预先认定这些 API 全部应纳入，也不得仅因传统游戏不需要而直接排除。**

---

## 0. 总览：准入建议汇总

| # | 方向 | 类型数（分母内） | 准入建议 | justification_level |
|---|---|---|---|---|
| 1 | RMI（远程观战） | 55 | **纳入**（API_DRIVEN，L2 候选） | L2→阶段四 L3 |
| 2 | JNDI（命名/服务发现） | 102 | **纳入**（API_DRIVEN，L2 候选） | L2→阶段四 L3 |
| 3 | TLS 与证书 | 97 | **纳入**（API_DRIVEN，L2 候选） | L2→阶段四 L3 |
| 4 | XML Signature | 58 | **纳入**（API_DRIVEN，L2 候选） | L2→阶段四 L3 |
| 5 | JDBC | 104 | **纳入**（API_DRIVEN，L2 候选，需自研驱动） | L2→阶段四 L3 |
| 6 | `javax.tools`（规则编译） | 24 | **纳入**（API_DRIVEN，L2 候选） | L2→阶段四 L3 |
| 7 | 注解处理 + `lang.model` | 112 | **纳入**（DEVELOPER_TOOLING，L2 候选） | L2→阶段四 L3 |
| 8 | Instrumentation（运行时增强） | 6 | **纳入**（DEVELOPER_TOOLING，L2 候选） | L2→阶段四 L3 |
| 9 | JMX（运行观测） | 204 | **部分纳入**：核心观测 L3 已论证；外围子包 L2 候选 | L3（48）+ L2 |
| 10 | Java Sound（音频） | 70 | **纳入**（API_DRIVEN，L3 已论证 39） | L3（39）+ L2（SPI 4） |
| 11 | `javax.print`（打印） | 135 | **纳入**（API_DRIVEN，L2 候选） | L2→阶段四 L3 |
| 12 | `java.awt`（无头绘图） | 382 | **部分纳入**：无头绘图 L3；GUI 组件 BIZ 排除 | L3（绘图）+ BIZ（GUI） |
| 13 | Clipboard（剪贴板） | 12 | **纳入**（API_DRIVEN，L2 候选，无头降级） | L2→阶段四 L3 |
| 14 | Swing 管理组件 | 797 | **仅保留 `javax.swing.undo`**（L3 已论证）；其余 GUI 组件 BIZ | L3（9）+ BIZ |

**净效果**：这些方向从「Excluded（2024 行）」中释放约 **753 个类型**进入 `Pending`（L2 候选），等待阶段四逐类型 L3 论证后转 `Planned`。另有 39 个音频类型保持 L3 已论证。

---

## 1. RMI —— 远程观战

1. **能力说明**：RMI 提供 JVM 间远程方法调用、对象序列化、动态代理、注册表与分布式 GC，是构建「只读观战」的标准 API。
2. **候选功能**：`> watch serve <port>` 暴露只读世界状态订阅；外部客户端可订阅星图、势力、账本快照；关闭后端口释放。
3. **涉及类型**：`java.rmi`（Remote/UnicastRemoteObject/MarshalledObject/Naming/异常 20）、`java.rmi.server`（ServerRef/RMIClassLoader/套接字工厂/UID 27）、`java.rmi.registry`（LocateRegistry/Registry 3）、`java.rmi.dgc`（Lease/VMID 3）、`javax.rmi.ssl`（2）。
4. **功能架构**：`fc-extensions/remote` → 导出只读接口 → 注册到 Registry → 客户端 LocateRegistry 发现 → 订阅世界快照；序列化过滤白名单限制可反序列化类型。
5. **运行环境要求**：`SERVICE_OPTIONAL`（可选联网），无网时静默禁用。
6. **第三方 Provider**：无（纯标准 API）。
7. **验收标准**：`> watch serve 1099` 后外部客户端可订阅世界状态；`> watch stop` 后端口释放。
8. **成本与风险**：中。序列化过滤不当有反序列化漏洞风险（用 ObjectInputFilter 白名单）；远程对象生命周期需管理。
9. **准入建议**：**纳入**，L2 候选，阶段四逐类型论证。

## 2. JNDI —— 命名与服务发现

1. **能力说明**：JNDI 提供统一的命名上下文（Name/Context/InitialContext）、对象工厂（ObjectFactory）、目录服务（DirContext）与事件通知。
2. **候选功能**：`> res locate <name>` 经 fc:// 命名上下文定位 mod 与配置资源；`> res list` 列出命名树。
3. **涉及类型**：`javax.naming`（41）、`javax.naming.directory`（18）、`javax.naming.spi`（12）、`javax.naming.event`（7）、`javax.naming.ldap`（22）、`javax.naming.ldap.spi`（2）。
4. **功能架构**：自研 `InitialContextFactory` 注册 fc:// 协议 → Context 定位资源 → DirContext 表达 mod 元数据目录树 → event 通知缓存失效。
5. **运行环境要求**：`NONE`（进程内命名上下文，无外部服务）。
6. **第三方 Provider**：无（自研 provider）。
7. **验收标准**：`> res locate fc:mod/combat` 返回 mod 资源；`> res list` 列出命名树。
8. **成本与风险**：中。LDAP 子包（22+2）无目录服务器，可仅用字符串解析子集（LdapName/Rdn）或整体排除——这是技术手段支配产品设计的风险点，须在阶段四明确"命名上下文 ≠ 目录服务器"。
9. **准入建议**：**纳入**（core naming 部分），LDAP 子包**建议排除**（无目录服务器场景，纯 BIZ）。

## 3. TLS 与证书

1. **能力说明**：JSSE（`javax.net.ssl`）提供 TLS/SSL 套接字、上下文、密钥/信任管理器；`java.security.cert` 提供 X.509 证书链解析与校验。
2. **候选功能**：远程端点（RMI/JMX）启用自签 TLS；`> mod verify` 校验签名 mod 的证书链。
3. **涉及类型**：`javax.net.ssl`（43）、`javax.net`（2）、`java.security.cert`（52）。
4. **功能架构**：SSLContext 加载密钥库 → TrustManager 校验证书链 → SSLEngine 加密通道；证书链用 CertPathBuilder/CertPathValidator 校验。
5. **运行环境要求**：`SERVICE_OPTIONAL`（可选加密）。
6. **第三方 Provider**：无（JCA 内置）。
7. **验收标准**：`> watch serve --tls` 后客户端需证书才能连接；`> mod verify` 校验签名 mod，篡改即拒。
8. **成本与风险**：中。证书管理复杂（自签 CA、吊销、有效期），需明确"自签 + 白名单"边界。
9. **准入建议**：**纳入**，L2 候选。

## 4. XML Signature

1. **能力说明**：`javax.xml.crypto` 提供 XML 数字签名（XMLSignatureFactory）、DOM 绑定、KeyInfo 解析。
2. **候选功能**：`> mod verify --xml` 验证 mod 清单的 XML 签名，防篡改。
3. **涉及类型**：`javax.xml.crypto`（15）、`dsig`（18）、`dsig.dom`（2）、`dsig.keyinfo`（8）、`dsig.spec`（12）、`dom`（3）。
4. **功能架构**：XMLSignatureFactory 创建验签上下文 → KeyInfo 解析公钥 → Reference 校验摘要。
5. **运行环境要求**：`NONE`。
6. **第三方 Provider**：无。
7. **验收标准**：篡改 mod 清单 XML 后 `> mod verify --xml` 报失败。
8. **成本与风险**：中低。与 HMAC 存档校验（SAVE-CRYPTO）是两套机制，需明确边界：XML 签名用于 mod 分发，HMAC 用于存档完整性。
9. **准入建议**：**纳入**，L2 候选。

## 5. JDBC

1. **能力说明**：JDBC 提供数据库连接（Connection/Statement/ResultSet）、数据源（DataSource）、离线结果集（RowSet）。
2. **候选功能**：`> query "SELECT ..."` 经只读 JDBC 驱动查询编年史，支持排序、分页、导出。
3. **涉及类型**：`java.sql`（55）、`javax.sql`（19）、`javax.sql.rowset`（12）、`rowset.serial`（10）、`rowset.spi`（8）。
4. **功能架构**：自研只读 JDBC 驱动（实现 Connection/Statement/ResultSet）→ 编年史表模型 → `query` 命令解析 SQL 子集。
5. **运行环境要求**：`PROVIDER_REQUIRED`——JDK 无内置驱动，须自研驱动或允许第三方嵌入式库。
6. **第三方 Provider**：**关键决策点**。要么自研只读驱动（纯标准 API，但工作量大），要么允许第三方嵌入式库（如 H2，破坏"核心零第三方依赖"）。
7. **验收标准**：`> query "SELECT * FROM events WHERE stardate > X"` 返回结果。
8. **成本与风险**：高。自研驱动工作量大；SQL 解析边界需严格限定；第三方库违反原约束。
9. **准入建议**：**纳入**（自研只读驱动方向），L2 候选，**需裁决驱动策略**。

## 6. `javax.tools` —— 玩家规则编译

1. **能力说明**：Java 编译器 API（JavaCompiler/StandardJavaFileManager/DiagnosticCollector）支持运行时编译。
2. **候选功能**：`> rule compile <file>` 编译受约束的玩家规则；编译错误行列回显。
3. **涉及类型**：`javax.tools`（24）。
4. **功能架构**：JavaCompiler 编译规则源 → DiagnosticCollector 收集错误 → 自定义类加载器加载 → 校验规则接口。
5. **运行环境要求**：`NONE`（需 JDK 而非 JRE）。
6. **第三方 Provider**：无。
7. **验收标准**：合法规则编译通过并生效；非法规则行列报错。
8. **成本与风险**：高。规则语言子集、安全边界（禁止反射/IO/网络）需严格设计；动态编译有安全风险。
9. **准入建议**：**纳入**，L2 候选，**需设计规则语言子集与安全边界**。

## 7. 注解处理 + `lang.model`

1. **能力说明**：注解处理器（AbstractProcessor）与语言模型（Element/TypeMirror）支持编译期源码分析。
2. **候选功能**：`fc-audit --proc` 经注解处理器收集 `@UsesApi` 标注，作为覆盖审计证据。
3. **涉及类型**：`javax.annotation.processing`（13）、`javax.lang.model`（3）、`lang.model.element`（29）、`lang.model.type`（18）、`lang.model.util`（49）。
4. **功能架构**：注解处理器遍历源码元素 → 收集类型引用 → 生成覆盖报告。
5. **运行环境要求**：`NONE`（编译期）。
6. **第三方 Provider**：无。
7. **验收标准**：`fc-audit --proc` 输出按包分组的类型引用清单。
8. **成本与风险**：低。这是阶段六审计的核心工具。
9. **准入建议**：**纳入**，L2 候选（版本化访问器 …6/7/8/9 应收紧为 …14）。

## 8. Instrumentation

1. **能力说明**：`java.lang.instrument` 提供 Java agent 与类文件转换（ClassFileTransformer）。
2. **候选功能**：`fc-audit --agent` 经 agent 记录类加载，提供运行期使用证据。
3. **涉及类型**：`java.lang.instrument`（6）。
4. **功能架构**：premain agent → ClassFileTransformer 记录加载的类 → 运行期证据落盘。
5. **运行环境要求**：`NONE`（JVM 启动参数）。
6. **第三方 Provider**：无。
7. **验收标准**：`fc-audit --agent` 输出运行期类加载清单。
8. **成本与风险**：中。agent 需 `-javaagent` 启动参数；与静态扫描有重叠。
9. **准入建议**：**纳入**（作为静态扫描的补充），L2 候选。

## 9. JMX —— 运行观测

1. **能力说明**：JMX（MBeanServer/ObjectName/StandardMBean）+ `java.lang.management`（平台 MXBean）提供运行观测。
2. **候选功能**：`> diag` 输出 tick 延迟、线程、堆、MBean 属性；`> diag dump` 落盘快照。
3. **涉及类型**：`javax.management`（73）、`java.lang.management`（20）、外围子包（relation 27/openmbean 25/modelmbean 12/monitor 10/timer 3/remote 18/remote.rmi 9/loading 7）。
4. **功能架构**：MBeanServer + StandardMBean 暴露世界指标 → 平台 MXBean 读资源指标 → `diag` 命令聚合展示。
5. **运行环境要求**：`NONE`（进程内）。
6. **第三方 Provider**：无。
7. **验收标准**：`> diag` 输出真实指标；`> diag dump` 落盘。
8. **成本与风险**：低（核心观测 L3 已论证）。外围子包（relation/monitor/timer/remote）需与单一 tick 时间模型协调，风险中。
9. **准入建议**：**核心观测纳入（L3 已论证 48）**；外围子包 L2 候选；`loading`（MLet 远程类加载）BIZ 排除。

## 10. Java Sound

1. **能力说明**：`javax.sound.sampled`（WAV 播放/采集）+ `javax.sound.midi`（MIDI 合成）+ SPI。
2. **候选功能**：事件音效（抵达/故障/重大事件）+ 氛围乐（切换星域）。
3. **涉及类型**：`sampled`（34）、`midi`（28）、`midi.spi`（4）、`sampled.spi`（4）。
4. **功能架构**：AudioSystem 取混音器 → AudioInputStream 解码 WAV → Clip 播放；MidiSystem 取默认音序器 → Sequence 合成氛围乐。
5. **运行环境要求**：`DEVICE_OPTIONAL`（无混音器/无 MIDI 设备时降级静默）。
6. **第三方 Provider**：无（内置）。
7. **验收标准**：事件触发播放音效；无设备时静默退出 0。
8. **成本与风险**：低。设备相关只能部分验证，以"降级行为"为证据。
9. **准入建议**：**纳入**，L3 已论证 39（播放链路）；SPI 子包（4+4）BIZ 排除（无自定义设备需求）。

## 11. `javax.print`

1. **能力说明**：打印服务发现（PrintServiceLookup）、打印作业（DocPrintJob）、打印属性（Attribute）。
2. **候选功能**：`> report --print` 渲染回合报告到打印服务。
3. **涉及类型**：`javax.print`（25）、`print.attribute`（26）、`print.attribute.standard`（76）、`print.event`（8）。
4. **功能架构**：PrintServiceLookup 发现服务 → DocPrintJob 提交 → 属性容器配置纸张/份数。
5. **运行环境要求**：`DEVICE_OPTIONAL`（无打印服务时降级 PNG/纯文本）。
6. **第三方 Provider**：无（本机 PDF 虚拟打印服务已检出）。
7. **验收标准**：有服务时打印报告；无服务降级 PNG。
8. **成本与风险**：中。CI/容器无打印服务，降级路径是必须的；与 XML/XSLT/PNG 导出功能重叠。
9. **准入建议**：**纳入**，L2 候选（API 覆盖优先下，打印作为多格式输出的一等能力）。

## 12. `java.awt` —— 无头绘图

1. **能力说明**：`Graphics2D`/`Shape`/`Paint`/`Stroke`/`Font`/`BufferedImage` 提供无头图像渲染。
2. **候选功能**：`> map --png` 渲染星图与报表。
3. **涉及类型**：`java.awt`（132）、`image`（55）、`geom`（36）、`font`（20）、`color`（7）、`image.renderable`（7）、其余 GUI（event/dnd/desktop/im/print 等）。
4. **功能架构**：BufferedImage + Graphics2D 绘制地点/航线/标注 → ImageIO 导出 PNG。
5. **运行环境要求**：`HEADLESS_OK`（无头可用）。
6. **第三方 Provider**：无。
7. **验收标准**：`> map --png` 产出含地点/航线/标注的 PNG。
8. **成本与风险**：低（无头绘图 L3 已论证）。
9. **准入建议**：**无头绘图纳入（L3 已论证）**；GUI 组件/事件/拖放/桌面/输入法 BIZ 排除。

## 13. Clipboard

1. **能力说明**：`java.awt.datatransfer` 提供数据传输（DataFlavor/Transferable）与系统剪贴板（Clipboard）。
2. **候选功能**：`> export --clipboard` 复制报告。
3. **涉及类型**：`java.awt.datatransfer`（12）。
4. **功能架构**：StringSelection 包装文本 → Clipboard.setContents；无头降级写文件。
5. **运行环境要求**：`HEADLESS_OK` + 降级（系统剪贴板无头不可用，数据模型可用）。
6. **第三方 Provider**：无。
7. **验收标准**：有剪贴板时复制成功；无头降级写文件。
8. **成本与风险**：低。系统剪贴板无头 HeadlessException（env-probe 证据）。
9. **准入建议**：**纳入**，L2 候选（数据模型 4 个无头可用，系统剪贴板 8 个无头降级）。

## 14. Swing 管理组件

1. **能力说明**：Swing 主要是 GUI 组件；`javax.swing.undo` 是纯模型（撤销/重做）。
2. **候选功能**：`> undo`/`> redo` 撤销未提交经营计划。
3. **涉及类型**：`javax.swing.undo`（9）+ 其余 GUI 组件（788）。
4. **功能架构**：UndoManager/CompoundEdit 管理指令撤销栈，纯模型无 GUI。
5. **运行环境要求**：`NONE`（undo 纯模型）；GUI 组件需图形环境。
6. **第三方 Provider**：无。
7. **验收标准**：`> undo` 撤销未提交计划，`> redo` 恢复；不回滚世界时间。
8. **成本与风险**：低（undo）；GUI 组件成本高且无场景。
9. **准入建议**：**仅 `javax.swing.undo` 纳入（L3 已论证 9）**；其余 GUI 组件（table/tree/text/plaf）BIZ 排除——纯 CLI 不建 GUI，这是"承载载体是 CLI"的必然，不是"为覆盖而排除"。

---

## 附：仍需裁决的设计决策

1. **JDBC 驱动策略**（§5）：自研只读驱动 vs 允许第三方嵌入式库 vs 放弃 JDBC 改 XPath/Stream。这是唯一破坏"核心零第三方依赖"的候选点。
2. **规则编译安全边界**（§6）：`javax.tools` 动态编译的规则语言子集与安全沙箱（禁反射/IO/网络/进程）。
3. **JNDI LDAP 子包去留**（§2）：LDAP（22+2）无目录服务器场景，建议 BIZ 排除，仅保留核心命名上下文。
4. **JMX 外围子包时间模型**（§9）：monitor/timer 引入独立时间源，与单一 Stardate 的协调方式。
5. **Swing 是否彻底排除**（§14）：除 undo 外，是否保留 table/tree 等"非 GUI 模型"（上轮已否决，本轮维持）。

## 附：技术风险清单

| 风险 | 影响 | 缓解 |
|---|---|---|
| 自研 JDBC 驱动工作量失控 | 阶段五排期 | 限定 SQL 子集（SELECT/WHERE/ORDER/LIMIT） |
| 动态编译安全 | 规则注入 | 白名单 + 禁反射/IO + 独立类加载器 |
| 反序列化漏洞 | 远程观战安全 | ObjectInputFilter 白名单 |
| 设备降级覆盖不全 | 音频/打印/剪贴板证据不足 | 以"降级行为"为验收标准 |
| L2 候选积压 | 阶段四论证工作量 | 按优先级（P1 先行）分批论证 |
