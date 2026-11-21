# 变更记录

`README.md` 由项目所有者冻结，是不可改动的事实与规则；自 v0.4.0 起，版本迭代记录在本文件
与本地 Git 提交信息中，格式沿用 README 的「版本号 + 日期 + 里程碑 + 编号条目」。

v0.0.1–v0.3.0 的完整记录仍在 README「版本变更迭代」一节，不在本文件重复。

## v0.11.2 2026-11-21 阶段五 M23 修订：TUI 输入与双窗口交互

1. **修复 TUI 无法输入命令**：输入从 `Reader.ready()` 轮询改为阻塞
   `readLine()`。Windows 控制台在回车前 `available()` 可能始终为 0，轮询会让
   TUI 完全收不到命令；该路径已删除。
2. **恢复列表命令可见性**：`select <N|up|down>`、`inspect`、`back` 在所有 TUI
   模式下都显示于按键提示并可直接输入，不再有只读可视化分支。
3. **双窗口 TUI 可交互**：`--split`/`--console` 主窗口不再是只读画面，与独立
   CLI 窗口一样可以提交命令，二者通过 `ConsoleHost` 操作同一世界。TUI 使用
   守护输入线程 + 100 ms 队列轮询，外部 `quit` 后立即退出，且不会偷走 CLI
   的后续输入。
4. **后台刷新**：共享 CLI Reader 时由守护刷新线程处理宿主通知；阻塞读命令
   期间，其他窗口的页面命令仍能自动刷新 TUI。
5. **测试**：TuiEngineTest 增加阻塞输入、双窗口队列输入/停止与后台刷新用例；
   当前共 33 套件、283 用例通过。
6. **审计基线**：类型 632/3836（16.5%）、包 88/176（50.0%）、模块 20/20、
   421 个 class、Preview 违规 0；报告已更新到 `docs/audit/`。
7. **说明**：README 保持冻结，版本记录在本文件与提交信息中。

## v0.11.1 2026-11-21 阶段五 M23 修订：失败条件与终局判定

1. **船员危机**：至少 6 名 CREW 同时处于饥饿、疲劳过度或士气低落时，
   进入「远征失败：船员危机」并冻结时间。
2. **债务违约**：玩家账户债务达到 5000 时进入「远征失败：债务违约」。
3. **声望崩溃**：任一势力声望降到 -50 或以下时进入「远征失败：声望崩溃」。
4. **优先级与反馈**：失败判定先于胜利判定；`goals` 面板新增 F1–F3 进度，
   TUI 目标页同步显示；失败同样记录 `EndingReached`。
5. **测试**：RuntimeSmokeTest 增加三类失败路径；当前共 33 套件、281 用例通过。
6. **说明**：README 保持冻结，版本记录在本文件。

## v0.11.0 2026-11-21 阶段五 M23：外缘星区、多路线胜利与跨窗口同步

1. **世界扩展**：默认世界从 16 地点/17 航线扩展为 **24 地点/27 航线**，
   新增 Altair、Procyon 外缘星区、回声裂隙、研究站与独立殖民地，并与原环线
   连通；知识、库存、生产与升级配置仍全部来自 XML。
2. **多路线胜利**：`GoalService` 在 D0–D4 阶段目标之外增加：
   - V1 商业霸权：信用点 ≥ 10000；
   - V2 外交联盟：任一势力声望 ≥ 60；
   - V3 星域测绘：全部地点达到 CHARTED；
   - 原有星门/信标工程终局保留。
   任一胜利路线达成即设置 ending、记录 `EndingReached` 并冻结时间；`goals`
   面板展示全部路线进度。
3. **目标可视化**：新增 TUI `goals` 页面，渲染同一份目标面板；页面命令与
   CLI `goals` 同步。
4. **跨窗口同步**：主窗口 `tui on` 现在接收 `ConsoleHost` 的命令通知并按
   100 ms 轮询刷新快照；其他 `window` 子窗口执行命令后，TUI 自动更新，
   不再需要手动重进。子窗口 `tui on` 通过远端快照同样解析 `goals` 页。
5. **测试**：新增三条胜利路线用例；配置/运行时测试更新为新世界规模；
   当前共 33 个套件、278 个用例通过。
6. **说明**：README 保持冻结，版本记录在本文件与提交信息中。

## v0.10.3 2026-11-21 阶段五 M22 修订：子窗口 TUI 与远端快照

1. **子窗口可直接开启 TUI**：`window` 派生的独立 CLI 窗口现在输入 `tui on`
   即可在本窗口进入可视化 TUI，`tui off` 返回 CLI；不再是主窗口专属。
2. **远端快照通道**：控制台协议新增只读快照请求；`TuiGame` 增加 `snapshot()`
   抽象，`RemoteTuiGame` 从宿主下载 `Snapshot` 后本地渲染。快照反序列化带
   类型白名单与深度/引用/数组长度限制，只接受本项目视图模型和基础集合类型。
3. **子窗口能力**：子窗口也可用 `window` 再派生窗口；TUI 空行/`refresh`
   重新拉取快照，多个 TUI 窗口看到同一世界。CLI 与 TUI 共用输入 Reader。
4. **测试**：`SplitConsoleTest` 增至 5 例，新增远端快照下载与命令往返；
   全量 33 套件 / 275 用例通过。
5. **说明**：README 保持冻结，版本记录在本文件。

## v0.10.2 2026-11-21 阶段五 M22 修订：多窗口刷新与聊天室离开竞态

1. **TUI 手动同步**：多窗口模式下其他窗口执行命令后，TUI 输入空行或
   `refresh` 会重新读取世界并重绘，解决“副窗口改了世界、TUI 仍显示旧状态”。
2. **聊天室离开竞态**：`QUIT` 现在先从在线表移除成员再回 `BYE`，`leave` 幂等；
   `chat say` 等待 `BYE` 后才关闭连接，避免随后 `chat roster` 偶发仍看到自己。
3. **测试稳定性**：`RuntimeSmokeTest` 显式结束测试 JVM，避免聊天室/RMI/JMX
   外围线程拖住 Maven；全量构建 33 套件 / 274 用例通过。
4. **文档**：README 保持冻结，版本记录在本文件。

## v0.10.1 2026-11-21 阶段五 M22：多窗口 CLI 与 TUI 切换

1. **主 CLI 成为权威宿主**：普通 REPL 现在通过 `ConsoleHost` 执行命令、
   存档/读档/PNG 与遥测刷新，外部 CLI 窗口通过回环控制台服务共享同一会话。
2. **`window` 命令**：首次执行时启动回环控制台宿主并另开一个独立 CLI 窗口，
   可重复执行打开多个窗口；窗口内可独立执行 `help`/`log`/`sail` 等命令，
   完整输出留在各自窗口。副窗口的 `quit` 只关闭该窗口，主窗口的 `quit` 结束游戏。
3. **`tui on/off`**：在主 CLI 窗口输入 `tui on` 进入可视化 TUI，`tui off` 返回 CLI；
   TUI 与 REPL 共用同一个输入 Reader，不丢缓冲输入；`quit` 在 TUI 中结束游戏，
   `tui off` 只退出界面。TUI 帮助页与 CLI 启动器帮助已列出该命令。
4. **测试**：新增 `tui off` 语义测试；当前共 33 个套件、274 个用例通过。
5. **说明**：多窗口/TUI 命令不进入重放日志；README 仍保持冻结，版本记录在本文件与提交信息中。

## v0.10.0 2026-11-21 阶段五 M21：诊断 Agent、认证中心与离线数据工作台

1. **运行时诊断 Agent**：新增独立 `fc-agent` 模块与 `-javaagent` 入口。
   `FcAgent.premain` 注册观察型 `ClassFileTransformer`（不改字节码）与
   `fc.agent:type=Diagnostics` MBean；提供已加载/累计加载/卸载类数、转换次数、
   重定义/重转换能力、采样时间、加载类名样本与 `getObjectSize` 浅层大小。
   CLI `diagnostics`（别名 `diag`）在 Agent 未加载时明确提示；不提供动态 Attach，
   也不执行未经核实的热替换。
2. **SASL 认证中心**：新增独立 `fc-auth` 模块。`SaslAuthenticator` 用标准
   `Sasl.createSaslClient/Server` 与 JCA Provider 注册自包含 `FC-CHALLENGE`
   （nonce + HMAC-SHA256，不传明文口令）；`LocalCallbacks` 处理
   Name/Password/Realm/AuthorizeCallback；认证结果统一为 `AuthIdentity`。
   支持机制发现、多轮 Token、错误口令/未知用户/不支持机制/授权不匹配与资源释放。
   CLI `auth mechanisms|sasl|kerberos|status` 提供入口，单机启动不依赖认证。
3. **Kerberos/GSS 入口**：`GssAuthenticator` 用 `GSSManager`/`GSSContext`/`GSSName`/
   `GSSCredential`/`Oid`/`MessageProp` 建立上下文与关闭释放；
   `KerberosEnvironment` 通过 `KerberosPrincipal`/`KeyTab` 探测 realm/KDC/keytab/
   服务主体。没有 KDC 时只报告未就绪，GSS 失败保留 major/minor 码，不伪造成功。
4. **离线编年史工作台**：新增 `ChronicleWorkbench`，复用只读 JDBC 驱动装载
   `CachedRowSet`；连接关闭后支持过滤（`FilteredRowSet` + `Predicate`）、
   复制排序、分页、与地点目录 `JoinRowSet` 连接；`WebRowSet` 标准 XML 导入导出，
   每行带 `schema_version=fc-workbench/1`，导入校验必需列/元数据/版本，
   非法 XML 与不兼容版本以 `SQLException` 拒绝。CLI `workbench show|filter|sort|
   join|export|import` 入口完整可用。
5. **构建与依赖**：新增 `fc-agent`/`fc-auth` 模块；`fc-cli` 可选依赖认证，
   `fc-agent` 为独立 Agent JAR（Manifest `Premain-Class` 等）。可选能力全部
   不参与核心单机启动。
6. **测试**：新增 `AgentProbe` 4 例（真实 `-javaagent` JVM）、`AuthTest` 6 例、
   `ChronicleWorkbenchTest` 5 例，运行时集成用例增加；当前共 33 个套件、
   273 个用例通过，未删除/跳过/放宽既有测试。
7. **审计基线**：类型 628/3836（16.4%）、包 88/176（50.0%）、模块 **20/20**、
   417 个 class、Preview 违规 0；四个目标模块均进入真实功能链路。
8. **文档**：新增 `docs/features/runtime-tooling.md`（启动、命令、API 对照、
   外部环境与降级说明）。README 仍按项目规则保持冻结，版本记录在本文件与提交信息中。

## v0.9.4 2026-11-21 阶段五 M20 修订：独立 CLI 窗口 UTF-8 编码

1. **修复 CLI 窗口乱码**：Windows 新控制台默认可能是本地代码页（如 936）；
   现在由启动器生成批处理，在执行 ConsoleClient 前先 `chcp 65001`，并给客户端
   显式传入 `-Dstdin.encoding=UTF-8 -Dstdout.encoding=UTF-8`。手工启动路径也会
   提示先执行 `chcp 65001`，ConsoleClient 启动时还会再尝试切换一次。
2. **测试 JVM 显式退出**：`SplitConsoleTest` 涉及回环服务器与子进程，主类结束时
   显式 `System.exit`，避免非守护线程导致 Maven 构建挂起。
3. **验证**：全量构建 30 套件 / 257 用例通过；双窗口实测 CLI 端完整接收
   96 行输出，中文正常，`quit` 同时关闭 TUI 与 CLI。

## v0.9.3 2026-11-21 阶段五 M20 修订：TUI/CLI 双窗口与完整输出

1. **双窗口模式**：新增 `--console` / `--split` 启动参数。本窗口启动只读可视化
   TUI；另开一个独立 CLI 窗口，通过回环地址 + 随机令牌接入同一个权威宿主。
   CLI 窗口显示命令的**完整输出**（`help`、`log all` 等不再被 TUI 截断），
   TUI 只负责舰桥/星图/舰队/情报等可视化。
2. **权威宿主与同步**：新增 `fc.runtime.ConsoleHost`，在同步锁内执行命令、
   存档/读档/PNG 与 JMX 遥测刷新；TUI 快照构建使用同一把锁。页面命令在 CLI
   执行后通知 TUI 刷新并切换页面；CLI 的 `quit` 同时结束两个窗口。
3. **独立 CLI 客户端**：新增 `SplitConsoleServer`（回环、令牌、Base64 帧）与
   `ConsoleClient`；Windows 用 `cmd /c start` 新建控制台窗口，其他平台打印可复制
   的手工命令；`FC_CONSOLE_NO_WINDOW=1` 供测试/排障时禁用自动开窗。
4. **移除 TUI 内嵌控制台**：按反馈恢复为单行反馈；长输出属于独立 CLI 窗口。
   单窗口 `--tui` 仍可直接输入命令。
5. **待办阻塞反馈**：`next` 被待决事项阻止时，TUI 状态行与情报页显示
   “规则阻塞”、决策 id、选项 id 与 `decisions`/`decide` 路径；组织条显示待办计数。
6. **确定性修复**：ZIP 证据包不再写入当前时间戳和 Properties 时间注释，
   同一编年史生成的 ZIP 字节稳定；这修复了 `ChronicleVaultTest` 偶发的
   “同内容应复用同一快照”失败（跨两秒边界时摘要不同）。测试连续重跑通过。
7. **测试**：新增 `SplitConsoleTest` 4 个用例（含启动独立 `ConsoleClient` 子进程
   验证完整输出）；当前共 30 个套件、257 个用例通过。
8. **审计基线**：类型 588/3836（15.3%）、包 82/176（46.6%）、模块 16/20、
   394 个 class、Preview 违规 0。

## v0.9.2 2026-11-21 阶段五 M20 修订：TUI 复用 CLI 命令与阻塞反馈

1. **移除 `screen` 导航**：页面切换直接复用 CLI 命令名
   `map`/`ships`/`colonies`/`research`/`market`/`log`/`status`/`decisions`/`help`；
   执行后先运行该 CLI 命令，再切到对应可视化页面，不再新增一套导航语法。
2. **CONSOLE 输出面板**：TUI 底部新增持久命令输出区（最近若干行），
   页面命令与所有 CLI 命令的完整输出都会进入这里；`help` 页面直接展示 CLI
   `help` 输出，不再只显示一行状态。
3. **待办阻塞可发现、可解决**：`next` 因待决事项停止时，TUI 自动切到情报页，
   在 CONSOLE 明确写出“世界规则阻塞”、待办数量、决策 id/选项 id 与
   `decisions` / `decide <决策id> <选项id>` 的解法；组织条显示待办计数。
   决策视图也直接展示 id 与选项，不再只给标题。
4. **实测修正**：此前进入 TUI 后 `help`/`log` 等命令没有可见反馈，且待办出现后
   `next` 看似无反应；根因是命令输出只保留最后一行、待决事项阻断没有界面提示。
   本次以 CONSOLE + 阻塞提示解决，游戏规则（有待办时不能推进）保持不变。
5. **测试**：新增“待办阻塞提示”回归；`TuiEngineTest` 11 个用例，
   当前共 29 个套件、253 个用例通过。
6. **审计基线**：类型 586/3836（15.3%）、包 82/176（46.6%）、模块 16/20、
   389 个 class、Preview 违规 0。

## v0.9.1 2026-11-21 阶段五 M20 修订：TUI 交互与重绘修复

1. **移除单字母缩写**：行输入模式不再把 `m/f/c/r/t/e/h` 等单字母当作页面切换，
   也不占用 CLI 已有命令名。页面切换改为显式
   `screen <bridge|map|fleet|colony|research|trade|intel|help>`；
   `help`、`map`、`market` 等仍保持原 CLI 语义。列表操作为
   `select <N|up|down>`、`inspect`、`back`，退出为 `quit`/`exit`。
2. **修复页面残留与输入回显混排**：行输入的终端回显不经过画布，差量渲染器感知不到；
   现在每次提交输入后强制全量重绘（`renderer.reset()`），清除回显文字与任何因换行/
   尺寸差异造成的旧页面残留，页面之间不再叠字。
3. **命令输入区重排**：移除提示行紧贴命令行的布局，命令行下方改为底部边框；
   操作提示合并到独立提示行，避免输入文字与说明混在一起。
4. **终端尺寸探测修正**：Windows 先查 PowerShell `$Host.UI.RawUI.WindowSize`
   （窗口可见尺寸），失败再回退 `mode con`；避免把控制台缓冲区高度（如 900 行）
   当成可见行数导致整屏滚动错位。默认尺寸改为 100×30，
   `FC_TUI_COLS/FC_TUI_ROWS` 仍可覆盖。
5. **TUI 存档集成**：`save`/`load`/`saves`/`png` 由 `--tui` 适配器处理，
   读档后替换当前会话并刷新遥测；不再依赖 REPL 的拦截逻辑。
6. **测试**：`TuiEngineTest` 增至 10 个用例，新增“页面切换无残留”与
   “行输入回显被全量重绘清除”回归；当前共 29 个套件、252 个用例通过。
7. **审计基线**：类型 586/3836（15.3%）、包 82/176（46.6%）、模块 16/20、
   389 个 class、Preview 违规 0。

## v0.9.0 2026-11-21 阶段五 M20：星际远征指挥终端（TUI）

1. **独立客户端模块**：新增 `fc-tui`，只依赖 `fc-world`/`fc-history`；通过
   `TuiGame`（状态 + 命令 + 市场/科研报告）与运行时解耦，`fc-runtime` 负责注入
   适配器。`SnapshotBuilder` 在每条命令后构建只读 `Snapshot`，页面渲染不触碰
   游戏对象，也不推进 Tick。
2. **渲染内核**：`TextWidth` 计算 CJK/emoji/组合字符宽度；`ScreenBuffer` 为字符 +
   样式双缓冲（宽字符两列、控制字符清洗）；`DiffRenderer` 只输出变化单元格，
   首帧/尺寸变化清屏，绘制期间关闭自动换行；ANSI 序列集中在 `Ansi`，主题为
   深空蓝黑 + 冷青 + 琥珀，提供 Unicode/ASCII 字符降级。
3. **终端适配**：`Terminal` 抽象 + `SystemTerminal`（备用屏幕、UTF-8、真彩 SGR）；
   尺寸优先 `FC_TUI_COLS/ROWS`，其次 `COLUMNS/LINES`、Windows `mode con`、
   Unix `stty size`，全部带失败与超时回退；`FC_TUI_ASCII`/`NO_COLOR` 控制降级。
4. **交互**：Java SE 21 无跨平台 Raw Mode，本版采用行输入——单字符行是快捷键
   （M/F/C/R/T/E/H/B/J/K/I/N/Q 与空格行推进），其余整行经 `TuiGame` 提交给 CLI；
   所有重要操作仍由游戏命令与已有校验执行；Q/EOF 恢复正常终端。
5. **页面**：舰桥总览（星图/舰队/情报/组织条）、星域导航（二维字符投影 +
   地点详情）、舰队、殖民地、科研、贸易、情报、帮助，统一详情弹窗、选中高亮与
   命令反馈行；星图只显示已知地点，渲染不做寻路或探索。
6. **启动集成**：`java -jar frontier-chronicle.jar --tui` 进入指挥终端；命令经
   `RuntimeSession.execute` 进入重放/存档逻辑，并刷新 JMX 遥测。
7. **测试**：新增 `TuiEngineTest` 8 个用例（含 ANSI 回放逐行重建、尺寸变化全量
   重绘、ASCII 降级与命令失败恢复）；当前共 29 个套件、250 个用例通过；
   既有测试未删除、未跳过、未放宽。
8. **审计基线**：类型 586/3836（15.3%）、包 82/176（46.6%）、模块 16/20、
   389 个 class、Preview 违规 0；`java.logging` 2/17（`Level`/`Logger` 用于
   客户端诊断）。
9. **已知限制**：本版不宣称 Raw Mode。Windows 经典控制台需 Windows Terminal 等
   ANSI 终端；行输入需回车提交。后续可在 `Terminal` 接口下增加平台适配 Provider，
   不影响页面与业务边界。

## v0.8.2 2026-11-21 阶段五 M19：货单剪贴板与 Transferable

1. **货单快照**：新增 `CargoManifest`，把当前星历、信用点、舰船数、地点数与结局
   固化为只读文本；实现 `Transferable`，同时支持自定义
   `application/x-fc-manifest;class=java.lang.String` 与 `DataFlavor.stringFlavor`，
   不支持的 flavor 抛 `UnsupportedFlavorException`。
2. **进程内剪贴板**：新增 `LocalClipboard`（继承 `Clipboard`），不依赖窗口系统；
   补齐基类缺失的语义——替换内容时通知旧 `ClipboardOwner.lostOwnership`，
   并向 `FlavorListener` 派发 `FlavorEvent`，单个监听器异常不影响复制。
3. **flavor 映射与校验**：`ManifestTransfer` 把货单 flavor 登记到 `SystemFlavorMap`
   （原生名 `FC_MANIFEST`），通过 `FlavorTable` 双向查询；`describe` 展示 MIME、
   可读名与原生映射；非法 MIME 以 `MimeTypeParseException` 拒绝。
4. **CLI**：新增 `manifest [copy|paste|text <文本>|flavors|status]`（别名 `cargo`），
   `manifest text` 使用 `StringSelection` 走纯文本路径；命令只操纵进程内状态，
   不进入重放日志，服务器无图形环境也可用。
5. **测试**：新增 `ManifestTransferTest` 4 个用例与运行时货单往返用例；
   当前共 28 个套件、242 个用例通过；既有测试未删除、未跳过、未放宽。
6. **审计基线**：类型 583/3836（15.2%）、包 82/176（46.6%）、模块 16/20、357 个 class、
   Preview 违规 0；`java.datatransfer` 12/12（100%）。

## v0.8.1 2026-11-21 阶段五 M18：XA 两阶段文件事务与双份留档

1. **文件 XA 资源**：新增 `FileXaResource`（`javax.transaction.xa.XAResource`）与
   `FcXid`（格式号 `0x4643`，16 字节随机全局事务 + 递增分支号）。prepare 只写同目录
   临时文件并 `force`，commit 用 ATOMIC_MOVE 发布，rollback 删除暂存；协议误用分别以
   `XAER_PROTO`/`XAER_NOTA`/`XAER_INVAL` 拒绝。
2. **持久恢复日志**：每个已准备分支写 `.fcxa-<global>-<branch>.xa`，记录 Xid 与临时
   文件名；新进程用同一目标构造资源后 `recover` 可找回未决分支并继续提交或回滚。
3. **两阶段协调器**：新增 `TwoPhaseFiles`，各分支独立 `start/end/prepare`，全部成功后
   逐个 commit；prepare 失败时回滚全部分支，commit 阶段失败报告 `XA_HEURMIX`，
   不把“部分成功”伪装成成功。
4. **双份导出**：`export <csv|xml|html|zip> <路径A> <路径B>` 先在临时目录生成一次，
   再用 XA 事务原子发布到两个路径；单路径导出复用同一提交路径。
   重复目标、空事务被拒绝；`export` 仍不进入重放日志。
5. **测试**：新增 `TwoPhaseFilesTest` 5 个用例，运行时导出用例增加双写与“无残留日志”
   断言；当前共 27 个套件、237 个用例通过；既有测试未删除、未跳过、未放宽。
6. **审计基线**：类型 571/3836（14.9%）、包 81/176（46.0%）、模块 15/20、353 个 class、
   Preview 违规 0；`java.transaction.xa` 3/3（100%），新增 `javax.transaction.xa` 包覆盖。

## v0.8.0 2026-11-21 阶段五 M17：JMX 通知、动态目录、监视器与真实时间心跳

1. **遥测主动通知**：`GameTelemetry` 改为继承 `NotificationBroadcasterSupport` 并实现
   `NotificationEmitter`；`StateHash` 变化广播 `AttributeChangeNotification`
   （old/new 值完整），命令计数变化广播 `fc.telemetry.command`，`userData` 携带
   `commandCount` 与 `day`；`getNotificationInfo` 声明两类通知，支持按类型过滤。
2. **动态 MBean 目录**：新增 `FrontierCatalog`（`fc.telemetry:type=Catalog`），
   以 `MBeanInfo`/`MBeanAttributeInfo`/`MBeanOperationInfo` 自描述 3 个只读属性；
   `snapshot` 返回 OpenType `GameSnapshot` 的 `CompositeData`，`find` 按 JMX 域枚举
   MBean 名称。`Main` 启动时与遥测一起注册并随退出注销。
3. **阈值监视器**：新增 `TelemetryMonitor`，注册 `GaugeMonitor`
   （`fc.telemetry:type=Gauge`），100 ms 采样观测 `Ships`/`CommandCount` 等整型属性，
   越过高/低阈值即广播 `jmx.monitor.gauge.high/low`；阈值类型与属性类型一致。
4. **真实时间心跳**：新增 `Heartbeat`，注册 `Timer` MBean
   （`fc.telemetry:type=Heartbeat`）按秒广播 `fc.heartbeat`；内部监听器统计触发次数，
   游戏 Tick 与真实时间严格分离，心跳不改变世界状态。
5. **直接 RMI 连接器与目录查询**：`RemoteJmx` 改为直接实例化 `RMIConnectorServer` 并写入
   标准套接字工厂属性，新增 `directConnect`（`RMIConnector`）；
   `allBeans`/`publishedBeans` 用 `QueryExp`（字符串 `Query.match("?*")`）枚举与
   筛选已发布状态的 MBean。
6. **CLI**：`jmx` 扩展为 `start|status|stop|beans|heartbeat [秒|stop|status]`；
   `jmx beans` 展示 fc 域 MBean 与已发布集合，`jmx heartbeat` 管理真实时间心跳，
   `jmx stop` 统一清理端点与心跳。命令仍不进入重放日志。
7. **测试**：新增 `TelemetryMBeansTest` 5 个用例，`RemoteJmxTest` 增加直接 RMI 客户端用例，
   运行时 JMX 用例扩展目录/心跳检查；当前共 26 个套件、232 个用例通过；
   既有测试未删除、未跳过、未放宽。
8. **审计基线**：类型 568/3836（14.8%）、包 80/176（45.5%）、模块 14/20、348 个 class、
   Preview 违规 0；`java.management` 42/195（21.5%）、`java.management.rmi` 2/9，
   新增 `javax.management.monitor`、`javax.management.timer`、
   `javax.management.openmbean`、`javax.management.remote.rmi` 包覆盖。

## v0.7.0 2026-11-21 阶段五 M16：Frontier Script 脚本引擎

1. **自研 javax.script 引擎**：JDK 21 不带脚本引擎，新增 `fc.extensions.FcScriptEngine`
   （`AbstractScriptEngine` + `Compilable`）与 `FcScriptEngineFactory`，经
   `META-INF/services/javax.script.ScriptEngineFactory` 注册；`ScriptEngineManager`
   可按 `frontier` / `fcs` 发现，扩展名 `.fcs`、MIME `text/x-frontier-script`。
2. **确定性批处理语法**：`#` 注释、`;`/换行分隔、`set`/`echo`/`repeat` 与普通游戏命令；
   `${name}` 在求值期替换，未知变量报错；`repeat` 上限 1000 次、嵌套 8 层。
3. **预编译**：`compile` 只做语法检查并返回可重复求值的 `CompiledScript`；
   语法错误以 `ScriptException` 带文件名与行号报告；宿主异常同样转为脚本错误。
4. **CLI**：新增 `script engines|eval <脚本>|run <文件>`；执行宿主回连 `Cli.execute`，
   可用变量与循环编排巡逻、巡检等重复操作。
5. **重放语义**：脚本展开的每条命令在执行前交给 `Cli.recordCommands`，由会话按普通
   命令规则过滤后写档；`script` 本身不进入存档，读档只重放展开后的命令，
   不依赖脚本文件仍在磁盘上。
6. **测试**：新增 `FcScriptTest` 6 个用例与运行时 1 个集成用例；当前共 25 个套件、
   226 个用例通过；既有测试未删除、未跳过、未放宽。
7. **审计基线**：类型 540/3836（14.1%）、包 76/176（43.2%）、模块 13/20、345 个 class、
   Preview 违规 0；`java.scripting` 覆盖 10/12（83.3%），新增 `javax.script` 包。

## v0.6.0 2026-11-21 阶段五 M15：TLS 加密信道与证书信任

1. **自签根与证书签发**：`fc.extensions.TrustAuthority` 只用公开 API 生成 RSA 密钥，
   按 RFC 5280 手工做 ASN.1/DER 编码，签发自签根、中间 CA、服务端与客户端叶子证书；
   叶子带基本约束、密钥用途与 serverAuth/clientAuth 扩展用途，服务端证书带
   `localhost` 主题备用名。证书用 `CertificateFactory` 回读，不依赖 `sun.security.x509`。
2. **PKIX 链构建与校验**：`CertificateChains` 用 `PKIXBuilderParameters` + `CertStore`
   从候选池构建「叶子 → 中间 → 根」路径，再用 `PKIXParameters` + 显式 `TrustAnchor`
   校验签名、有效期与用法；过期证、异根、缺失中间证书均被拒绝。吊销检查关闭，
   语义明确为「自签 + 显式信任锚」。
3. **密钥库与 TLS 上下文**：`SecureChannel` 装配 PKCS#12（私钥条目带完整链、信任库只放根）、
   `KeyManagerFactory` / `TrustManagerFactory` 与 `SSLContext`；支持文件读写、
   对端链与叶子指纹提取。
4. **聊天中继双向 TLS**：`ChatRelay.start(端口, 历史, SSLContext)` 改用
   `SSLServerSocket` 并强制客户端证书；`connectTls` 完成握手后才建立行协议会话，
   匿名客户端在握手阶段被拒。明文中继行为不变。
5. **CLI**：新增 `trust init|status [目录]`，生成并复核根/服务端/客户端 PKCS#12；
   `chat open [端口] tls <目录>` 以该信任库启动双向 TLS 中继，`chat say` 自动使用
   客户端证书。`trust`、`chat` 不进入重放日志。
6. **测试**：新增 `TrustAuthorityTest` 6 个用例、`TlsChatTest` 5 个用例与运行时
   1 个集成用例；当前共 24 个套件、219 个用例通过；既有测试未删除、未跳过、未放宽。
7. **审计基线**：类型 530/3836（13.8%）、包 75/176（42.6%）、模块 12/20、336 个 class、
   Preview 违规 0；新增 `javax.net.ssl`、`java.time.format`、`java.time.temporal` 包覆盖。

## v0.5.9 2026-11-21 阶段五 M14：fc:// 统一资源命名上下文

1. **命名格式**：`fc.extensions.FcNaming` 实现 `fc:<scope>/<path>[/<subpath>...]`，
   顶级 scope 固定为 `mod`/`config`/`save`/`service`/`chronicle`；未知 scope、空段、
   `.`/`..` 一律在解析期拒绝。
2. **JNDI 实现**：自研 `InitialContextFactory` 返回进程内 `Context`，不连接外部目录服务器；
   `InitialContext` 通过环境变量选择工厂，每个 `open()` 得到独立命名空间。
3. **名称与上下文**：支持 `Name`/`String` 两种入口、相对/绝对名称、`createSubcontext`/
   `destroySubcontext`、`list`/`listBindings`（`NamingEnumeration`）、`composeName`/
   `getNameParser`、`rename`/`rebind`/`unbind`；错误使用 JNDI 异常族
   （`InvalidNameException`、`NameNotFoundException`、`NameAlreadyBoundException`、
   `NotContextException`）。
4. **服务引用**：`FcService` 实现 `Referenceable`，绑定的是 `Reference`；
   `NamingManager` + 自研 `ObjectFactory` 在查找时把引用重建为 `FcService`，
   不把实现类实例直接塞进命名树。
5. **变更通知与链接**：`NamespaceChangeListener` 收到 `NamingEvent`（新增/移除/重绑）；
   `LinkRef` 由 `lookupLink` 解引用。
6. **CLI**：新增 `res list [scope]|locate <fc:...>|register <fc:...> <类型> <端点>|
   unregister <fc:...>|services`；`chat open` 与 `jmx start` 自动登记
   `fc:service/chat` / `fc:service/jmx`，停止时自动解除。命令不进入重放日志。
7. **测试**：新增 `FcNamingTest` 6 个用例与运行时 1 个集成用例；当前共 22 个套件、
   207 个用例通过；既有测试未删除、未跳过、未放宽。
8. **审计基线**：类型 498/3836（13.0%）、包 72/176（40.9%）、模块 12/20、330 个 class、
   Preview 违规 0。

## v0.5.8 2026-11-21 阶段五 M13：联机通信第一阶段与聊天室中继

1. **TCP 消息面**：`fc.extensions.ChatRelay` 只绑定回环地址，以 UTF-8 行协议提供
   `NICK`/`MSG`/`WHO`/`HIST`/`PING`/`QUIT`；服务端推送 `WELCOME`/`OK`/`JOIN`/`MSG`/
   `PART`/`ROSTER`/`HISTORY`/`PONG`/`ERROR`，每条消息带全局递增序号。
2. **并发模型**：`ServerSocket` + 虚拟线程逐连接处理，`ConcurrentHashMap` 维护在线成员与
   昵称占用，`AtomicLong` 分配成员号与消息序号；昵称大小写不敏感去重，消息与历史条数有上限。
3. **历史与成员表**：进程内保留最近 `historyLimit` 条消息（默认 200），`WHO`/`HIST` 可查；
   中继身份（`id == 0`）可广播系统公告。
4. **RMI 控制面**：成员表、消息计数与广播能力通过本机 RMI 注册表暴露为 `fc-chat-control`；
   消息面与控制面都只使用回环套接字工厂，不做跨机暴露。
5. **CLI**：新增 `chat open [端口]|say <昵称> <文本>|history [条数]|roster|close`；
   `say` 走真实 TCP 往返，命令不进入重放日志，退出时统一关闭中继。
6. **测试**：新增 `ChatRelayTest` 6 个用例与运行时 1 个集成用例；当前共 21 个套件、
   200 个用例通过；既有测试未删除、未跳过、未放宽。
7. **审计基线**：类型 472/3836（12.3%）、包 69/176（39.2%）、模块 11/20、320 个 class、
   Preview 违规 0。

## v0.5.7 2026-11-21 阶段五 M12：回环 JMX 远程管理

1. **RMI 注册表**：`fc.extensions.RemoteJmx` 把平台 `MBeanServer` 暴露到本机 RMI 注册表，
   端点 URL 为 `service:jmx:rmi:///jndi/rmi://127.0.0.1:<端口>/jmxrmi`，可被 `jconsole`
   或 `JMXConnectorFactory` 直接连接。
2. **只绑定回环**：注册表与连接器都使用自建的 `RMIServerSocketFactory` /
   `RMIClientSocketFactory`，服务端只监听 `InetAddress.getLoopbackAddress()`，客户端也只连回环；
   不依赖 `java.rmi.server.hostname`，不做跨机暴露，认证边界由操作系统本地账户承担。
3. **生命周期**：`RemoteJmx.Server` 实现 `AutoCloseable`；`close()` 停止连接器并
   `unexportObject` 注销注册表，可重复调用；启动失败时清理半初始化资源；端口越界在启动前拒绝，
   端口 0 自动选择空闲回环端口。
4. **读写语义**：`snapshot` 读取 `fc.telemetry:type=Game` 的八个属性，MBean 未注册时明确失败；
   `setAttributes` 按 JMX 语义返回真正写入的属性，只读属性被跳过而不是抛异常。
5. **CLI**：新增 `jmx [start [端口]|status|stop]`（`jmx <端口>` 等价于启动）；`status`
   自连接读取遥测，未注册遥测时回退显示 MBean 数量；命令与端点生命周期退出时一并清理，
   且不进入重放日志。
6. **测试**：新增 `fc-extensions.RemoteJmxTest` 5 个用例与运行时 1 个集成用例；当前共
   20 个套件、193 个用例通过；既有测试未删除、未跳过、未放宽。
7. **审计基线**：类型 464/3836（12.1%）、包 69/176（39.2%）、模块 11/20、312 个 class、
   Preview 违规 0。

## v0.5.6 2026-11-21 阶段五 M11：证据封存与 PKCS#12 密钥库

1. **密钥库**：`fc.history.seal.EvidenceSeal` 用 PKCS#12 保存单个 AES-256 `SecretKeyEntry`
   （别名 `evidence`）；首次调用生成密钥并落盘，之后复用；POSIX 文件系统上权限收紧为属主读写。
2. **封存**：`SealedObject` + `AES/GCM/NoPadding` 把文件封成 `.sealed` 包，带 `FCSEAL1` 魔数头；
   同一明文两次封存因随机 IV 不同而产生不同密文；写出采用临时文件 + 原子替换。
3. **反序列化安全**：读取时先校验魔数，再用 `ObjectInputFilter` 白名单（仅 `SealedObject`、
   `String`、`byte[]`）解析，其他序列化类一律拒绝。
4. **错误语义**：错误密钥、篡改密文、错误密钥库口令、缺失密钥库/别名、魔数不符、空负载
   均被明确拒绝；口令数组用后清零，不被日志输出。
5. **CLI**：新增 `seal <文件> [输出]` 与 `unseal <封存文件> [输出]`；默认密钥库
   `~/.frontier-chronicle/evidence.p12`，可用 `-Dfc.seal.keystore` 与 `-Dfc.seal.password` 覆盖；
   两个命令都不进入重放日志。
6. **测试**：新增 `EvidenceSealTest` 6 个用例与运行时 1 个集成用例；当前共 19 个套件、
   187 个用例通过；既有测试未删除、未跳过、未放宽。
7. **审计基线**：类型 443/3836（11.5%）、包 65/176（36.9%）、模块 10/20、308 个 class、
   Preview 违规 0。

## v0.5.5 2026-11-21 阶段五 M10：档案库镜像与并发安全

1. **内容寻址快照**：`fc.history.vault.ChronicleVault` 把编年史写成
   `chronicle-<日>-<摘要前 12 位>.zip`（内容即 ZIP 证据包）；同一内容重复写入幂等，
   不产生第二份文件、不重复记索引。
2. **并发写入**：档案库目录内的 `.vault.lock` 用 `FileChannel.tryLock` 做跨进程排他，
   同一时刻只允许一个写入者；索引追加另用 `FileChannel.lock` 保护。锁被占用时抛
   `VaultException` 并拒绝写入。
3. **异步落盘**：快照字节经 `AsynchronousFileChannel` + `CompletionHandler` 分批写入，
   `CountDownLatch` 收口、`FileChannel.force(true)` 落盘；失败不会留下半个索引行。
4. **索引与校验**：`index.tsv` 逐行记录文件、日、字节数、SHA-256、事件数与 CRC32；
   读取用 `MappedByteBuffer` + UTF-8 严格解码，坏行/坏 CRC 直接拒绝；`verify` 用直接
   `ByteBuffer` 流式计算摘要并核对字节数。
5. **变更监视**：`watch` 用 `WatchService` 等待新快照落盘，忽略索引、锁与临时文件；
   超时返回空列表而不阻塞。
6. **CLI**：新增 `vault [write|list|check|watch] [目录]`（别名 `archive`），默认目录 `vault`；
   与其他写文件命令一样不进入重放日志。
7. **测试**：新增 `ChronicleVaultTest` 6 个用例与运行时 1 个集成用例；当前共 18 个套件、
   180 个用例通过；既有测试未删除、未跳过、未放宽。
8. **审计基线**：类型 422/3836（11.0%）、包 65/176（36.9%）、模块 10/20、304 个 class、
   Preview 违规 0。

## v0.5.4 2026-11-21 阶段五 M9：配乐工坊与离线音频

1. **确定性作曲**：`fc.extensions.Soundtrack` 以 `StateHasher` 摘要混合星历作为种子，
   `Random(seed)` 驱动五声音阶旋律、铺底三和弦与打击乐；同一世界状态生成逐事件相同的 MIDI。
2. **MIDI 结构**：4 小节 4/4、PPQ 480、96 BPM，三条轨道（长笛 73 / 铺底 89 / 第 10 通道鼓组）；
   写入曲名、速度 `0x51`、拍号 `0x58`、音量与声像控制器、EOT；`MidiSystem.write` 输出 SMF type 1，
   `readMidi`/`format` 回读校验。
3. **离线合成**：`renderWav` 按十二平均律换算频率，正弦波 + 起音/释音包络混音、峰值限幅，
   经 `AudioSystem.write` 输出 44.1 kHz/16-bit/单声道 WAV；不需要声卡。
4. **设备探测**：`sequencerAvailable()` 在无设备环境返回 `false`；播放交给外部 MIDI 播放器。
5. **CLI**：新增 `music [mid|wav] [路径]`（别名 `soundtrack`），默认 `music.mid`/`music.wav`；
   输出轨道数、音符数、时长与种子；与 `export`/`atlas` 一样不进入重放日志。
6. **测试**：新增 `SoundtrackTest` 5 个用例与运行时 1 个集成用例；当前共 17 个套件、
   173 个用例通过；既有测试未删除、未跳过、未放宽。
7. **审计基线**：类型 400/3836（10.4%）、包 64/176（36.4%）、模块 10/20、299 个 class、
   Preview 违规 0。

## v0.5.3 2026-11-21 阶段五 M8：档案签章与证据完整性

1. **XML Signature**：`fc.history.sign.EvidenceSignature` 生成自包含证据 XML
   （`urn:fc:evidence`）：负载以 Base64 封装在 `#chronicle` 元素中，JSR-105 引用摘要
   SHA-256、签名 RSA-SHA256、独占 C14N，`KeyInfo` 内嵌 RSA `KeyValue`。
2. **验签**：内嵌公钥验签（完整性）与传入可信公钥验签（身份）两种路径；
   篡改负载、错误公钥、缺失/伪造文件均返回无效；`KeySelector` 从 `KeyInfo` 取键。
3. **密钥管理**：2048–8192 位 RSA 生成（`RSAKeyGenParameterSpec` + `SecureRandom`）；
   X.509/PKCS#8 DER 读写（`KeyFactory`）；SHA-256 公钥指纹；`loadOrCreateKeyPair`
   首次落盘、之后复用。
4. **安全解析**：关闭 DOCTYPE/外部实体；转换工厂关闭外部 DTD 与样式表。
5. **CLI**：`sign <文件> [输出]` 与 `verify <证据文件>`；默认密钥目录
   `~/.frontier-chronicle/keys`（可用 `-Dfc.keys.dir` 覆盖），不进入重放日志。
6. **测试**：新增 `EvidenceSignatureTest` 5 个用例与运行时 1 个集成用例；
   当前共 16 个套件、167 个用例通过；既有测试未删除、未跳过、未放宽。
7. **审计基线**：类型 386/3836（10.1%）、包 63/176（35.8%）、模块 10/20、296 个 class、
   Preview 违规 0。

## v0.5.2 2026-11-21 阶段五 M7：星图工坊与出版级制图

1. **制图管线**：`fc.extensions.MapAtlas` 在无头环境绘制出版级星图：渐变星野、
   `ConvolveOp` 模糊星云、`RescaleOp` 亮度、曲线航线（`QuadCurve2D`/`CubicCurve2D`）、
   多形状节点（星形 `Path2D`、`Ellipse2D`、`RoundRectangle2D`、`Rectangle2D`、`Polygon`、`QuadCurve2D`）、
   罗盘、比例尺、图例面板（`Area` 差分描边）与 `AffineTransformOp` 缩略内嵌图。
2. **排版**：`GlyphVector.getOutline` 渐变标题、`AttributedString`+`TextAttribute.TRACKING`
   副标题、`TextLayout` 绘制、`FontMetrics` 定位与标签底衬。
3. **多格式与元数据**：PNG（tEXt）与 JPEG（COM）写出，`ImageWriter`/`ImageWriteParam`
   控制质量；`readTextMetadata` 读回；`ImageReadParam.setSourceRegion` 读取局部；
   JPEG 前用 `ColorConvertOp` 转 sRGB。
4. **图像工具**：锐化/模糊/亮度/缩略图/区域读取/sRGB 转换均为公开静态方法，有独立测试。
5. **CLI**：新增 `atlas [png|jpeg] [路径]`（别名 `chart`），默认输出 `atlas.png`；
   与 `query`/`export` 一样不进入重放日志。
6. **测试**：新增 `MapAtlasTest` 6 个用例与运行时 1 个集成用例；当前共 15 个套件、
   161 个用例通过；既有测试未删除、未跳过、未放宽。
7. **审计基线**：类型 350/3836（9.1%）、包 57/176（32.4%）、模块 9/20、293 个 class、
   Preview 违规 0。

## v0.5.1 2026-11-21 阶段五 M6：编年史查询、导出与只读 SQL

1. **事件投影**：`fc.history.query.EventView` 把 sealed `GameEvent` 投影为稳定字段
   （日、日期、类型、类别、地点、主体、因果、结果、摘要），作为对象、文件、SQL 三种读取入口的
   唯一数据形态。
2. **查询模型**：`ChronicleQuery` 支持日区间、类别/类型、地点（`3` 与 `L3` 等价）、主体、
   全文检索，按日稳定排序后分页；条件非法时在构造期拒绝。
3. **文件导出**：CSV；确定性 StAX XML（内置 XSD 校验、XPath 计数）；内置 XSLT 生成 HTML
   报表；ZIP 证据包固定包含 XML/CSV/HTML 与 `manifest.properties`。解析器拒绝 DOCTYPE 与
   外部实体，样式表工厂关闭外部访问。
4. **只读 JDBC 驱动**：`jdbc:fc:chronicle[:会话名]`，通过 `META-INF/services/java.sql.Driver`
   自动注册，也可用 `ChronicleDataSource`。基于 `java.lang.reflect.Proxy` 实现
   `Connection`/`Statement`/`PreparedStatement`/`ResultSet`/元数据；SQL 子集支持
   `SELECT` 列清单或 `*`、`COUNT(*) AS n`、`AND` 条件、比较与 `LIKE`、单列排序、`LIMIT/OFFSET`。
   写入与 DDL 一律抛 `SQLFeatureNotSupportedException`；未绑定会话抛 `SQLException`。
5. **CLI**：新增 `query <SQL>` 与 `export <csv|xml|html|zip> [路径]`（默认 `chronicle.<格式>`）。
   两者与存档命令一样不进入重放日志。
6. **测试**：新增 `fc.history.HistoryQueryTest` 10 个用例与 `RuntimeSmokeTest` 1 个集成用例；
   当前共 14 个套件、154 个用例通过；既有测试未删除、未跳过、未放宽。
7. **审计基线**：类型 305/3836（8.0%）、包 53/176（30.1%）、模块 9/20、291 个 class、
   Preview 违规 0。证据见 `docs/audit/`。

## v0.5.0 2026-11-21 阶段五 M5：持久化、扩展与审计

1. **存档/读档**：`fc-persist.SaveRepository` 采用「世界来源 + 有序命令日志 + 状态哈希」重放路线；
   `.fcsave` 为单文件 JAR（`save.properties` + `game.bin`），读档重放后比对 `StateHasher`，不一致即失败。
2. **存档安全**：可选 `PBKDF2WithHmacSHA256`（12 万次）+ `AES/GCM/NoPadding` 加密；
   明文存档始终带 SHA-256 校验；槽位名校验、读取签名验证、临时文件落盘后原子替换。
3. **偏好与本地化**：`PreferenceStore`（`java.util.prefs`，按档案隔离）、`Localization`
   （UTF-8 `ResourceBundle` + `MessageFormat`，关闭默认 Locale 回退，中英文资源包）。
4. **扩展 SPI**：`FrontierMod` / `ModContext` / `ModDescriptor`；`ModLoader` 支持 JAR 与目录、
   API 版本与语义化版本校验、重复 id 拒绝、统一代码签名者要求与签名验证。
5. **运行时编译与宏**：`ModCompiler`（JSR-199 内存/落盘编译、诊断格式化）；
   `Macro`（注释、元数据、`${param}` 展开、保存/读取、顺序执行）。
6. **观察与导出**：`GameTelemetry` 注册 JMX `fc.telemetry:type=Game`；`StatusServer`
   仅绑定回环地址提供 `/status` JSON；`StarMapRenderer` 输出确定性 PNG；`SoundBank` 合成提示音并可优雅降级。
7. **运行时集成**：启动器新增 `save <槽位> [口令]`、`load`、`saves`、`png [路径]`；
   启动参数 `--world`、`--serve[=端口]`；自动装载 `mods/`；每条命令后刷新 JMX。
8. **API 使用审计**：`fc-audit` 读取阶段一冻结基准，解析 class 常量池与描述符/签名，
   生成 Markdown 与 CSV 证据；检测 Preview class 与 `EXCLUDED_PREVIEW` 引用，违规退出码 1。
   审计阶段移至反应堆末尾；证据落在 `docs/audit/`。
9. **当前审计基线**：类型 256/3836（6.7%）、包 48/176（27.3%）、模块 8/20、267 个 class、
   Preview 违规 0。该数字作为后续覆盖扩张的诚实起点，不设硬阈值。
10. **测试**：13 个套件 143 个用例通过（新增 persist 6、extensions 9、runtime persistence 6、audit 5）。

## v0.4.0 2026-11-21 阶段五 M4：科研、外交与探索

1. **统一模型补齐**：`SkillSet` 六技能（驾驶/工程/科研/医疗/贸易/探索）进入生产、航行、科研、
   交易、医疗与探索；`BigDecimal` 工作进度；技能成长按主体与 30 天周期判定。
2. **舰船与船员**：船型、货舱、铺位、船体状态；船员随船移动、同停泊点调动、维修消耗材料与信用点。
3. **设施与岗位**：设施类型、工人容量、实验室与船坞岗位；配方、生产与技能系数联动。
4. **配置化**：技能、船型、设施、事件参数、科技树、初始知识、税率、委托与终局工程均进入
   XML/XSD 与语义校验；默认世界同步迁移。
5. **迷雾与探索**：知识状态、`scan` 近距扫描、`survey` 勘察与取消、发现物、科研数据；
   知识状态进入 `StateHasher`。
6. **科研与效果**：科技树、前置条件、科研项目、`TechEffect` 作用于航线、配方、货舱、工作速度、
   税率与勘察；打通「高效曲率线圈 → 航线变短 → 边远矿点可运营 → 矿物供给增加 → 价格回落」因果链。
7. **外交与冲突**：声望分级、赠礼、贸易/互不侵犯/同盟条约、税率减免、委托生成/接取/交付/到期、
   威胁与冲突结算、船体损伤与势力关系漂移。
8. **目标与终局**：D0–D4 阶段目标、船坞订单与舰队扩容、星门/边境信标工程、双终局与终局后冻结时间。
9. **航行风险**：航段风险、事件修正、船型固有权风险与驾驶技能共同决定航程事故，结果可重放。
10. **新命令**：`scan`、`survey`、`research`、`assign`、`repair`、`order`、`project`、
    `diplomacy`、`gift`、`treaty`、`threaten`、`commission`、`borrow`、`repay`。
11. **测试**：9 个套件 117 个用例通过，其中 `M4GameplayTest` 9 个用例覆盖完整 M4 玩法链；
    既有测试未删除、未跳过、未放宽。
