# 五项决策落地 + 未覆盖模块清单

> 本轮：用户给出五项决策，并要求「列出所有目前还没有覆盖的模块级，我自己来想功能」。
> 本文件前半是决策落地记录，后半是**未覆盖模块清单**（供用户自行设计功能）。

---

## 一、五项决策落地

| # | 决策 | 落地位置 | 状态 |
|---|---|---|---|
| 1 | JDBC Driver = **核心自研 CSV Driver**（不选 H2/SQLite） | `type-keep.tsv`：JDBC justification 改为"自研只读 CSV Driver，实现 Driver/Connection/Statement/ResultSet 接口"；`runtime_requirement` 从 `PROVIDER_REQUIRED` 改为 `NONE` | ✅ |
| 2 | 规则编译 = **本地可信 Mod 默认充分权限，不承诺进程内沙箱** | 见 §1.2 信任模型说明 | ✅ |
| 3 | Swing = **正式立项可选管理客户端，模型层/视觉层分阶段审计** | `feature-anchors.tsv`：ADMIN-DESKTOP priority P3→**P2**，acceptance 加"模型层先审计、视觉层后审计" | ✅ |
| 4 | JMX 时间 = **现实时间与游戏时间分离，形成正式规范** | 新增 `docs/phase3/time-model-spec.md` | ✅ |
| 5 | 命名 = **fc:// 作为统一资源命名体系，明确解析/注册/生命周期** | 新增 `docs/phase3/naming-spec.md` | ✅ |

### 1.1 JDBC 自研 CSV Driver（决策 1）

- **定位**：`java.sql` 的 Driver/Connection/Statement/ResultSet 是**标准 API 接口**（在分母内），由**项目自有代码实现**只读 CSV Driver。这是"标准 API 接口的实现"，不是"第三方 Provider"，也不是"自研假 Driver 换覆盖率"——Driver 服务的是真实的 `> query "SELECT ..."` 编年史 CSV 查询。
- **边界**：SQL 子集限定（SELECT/WHERE/ORDER/LIMIT），只读，不写。
- **runtime_requirement**：`NONE`（自研，无第三方依赖）。

### 1.2 规则编译信任模型（决策 2）

- **定位**：`javax.tools` 编译的是**本地可信 Mod**，默认给予充分权限；**不承诺进程内沙箱**。
- **明确**：不把"独立 ClassLoader / 黑名单 / 反射限制"冒充可靠的恶意代码隔离（遵守约束 4）；不可信代码的隔离是**进程外/文档化信任边界**的问题，不在本项目进程内实现。
- **后果**：`RULE-COMPILE` 的 L2 候选在阶段四论证时，api_role 是"编译本地可信 Mod"，不写"安全沙箱"相关职责。

---

## 二、未覆盖模块清单（供你设计功能）

> 定义：`Planned=0` 的 JDK 模块 = "还没有任何类型达到 L3 论证"。
> 共 **12 个模块**。每项给出：分母类型数、包构成、当前状态（Pending=已设计待论证 / Excluded=已排除）、一句话现状，供你决定是否给它一个真实功能。

### 2.1 `java.compiler` —— 136 类型（全 Pending）

| 包 | Pending | 现状 |
|---|---|---|
| `javax.tools` | 24 | RULE-COMPILE：受信任 Mod 编译 |
| `javax.annotation.processing` | 13 | PROC-ANNOT：注解处理器 |
| `javax.lang.model` / `element` / `type` / `util` | 3+29+18+49 | PROC-ANNOT：语言模型遍历 |

**一句话**：编译工具链（编译器 + 注解处理 + 语言模型），当前全 L2 候选。你若要给它功能，方向是"编译期工具"（规则编译、覆盖审计注解处理）。

### 2.2 `java.datatransfer` —— 12 类型（全 Pending）

| 包 | Pending | 现状 |
|---|---|---|
| `java.awt.datatransfer` | 12 | CLIPBOARD-EXPORT：DataFlavor/Transferable + 系统剪贴板 |

**一句话**：数据传输模型 + 剪贴板，当前 L2 候选（无头降级）。方向是"数据交换"。

### 2.3 `java.instrument` —— 6 类型（全 Pending）

| 包 | Pending | 现状 |
|---|---|---|
| `java.lang.instrument` | 6 | AGENT-INSTR：运行时 agent 记录类加载 |

**一句话**：Java agent 与类文件转换，L2 候选。方向是"运行时诊断/审计"。

### 2.4 `java.management.rmi` —— 9 类型（全 Pending）

| 包 | Pending | 现状 |
|---|---|---|
| `javax.management.remote.rmi` | 9 | REMOTE-JMX：RMI 传输的远程管理端点 |

**一句话**：JMX over RMI 连接器，L2 候选。方向是"远程管理"。

### 2.5 `java.net.http` —— 22 类型（全 Pending）

| 包 | Pending | 现状 |
|---|---|---|
| `java.net.http` | 22 | HTTP-TELEMETRY：HttpClient/WebSocket 遥测 |

**一句话**：HTTP 客户端 + WebSocket，L2 候选（离线降级）。方向是"遥测上传/公告拉取"。

### 2.6 `java.rmi` —— 55 类型（41 Pending + 14 Excluded）

| 包 | Pending | Excluded |
|---|---|---|
| `java.rmi` | 17 | 3（废弃） |
| `java.rmi.server` | 17 | 10（废弃桩/骨架） |
| `java.rmi.registry` | 2 | 1（废弃） |
| `java.rmi.dgc` | 3 | 0 |
| `javax.rmi.ssl` | 2 | 0 |

**一句话**：远程方法调用 + 分布式 GC + 注册表，L2 候选（REMOTE-WATCH）。方向是"远程只读观战"。

### 2.7 `java.scripting` —— 12 类型（全 Pending）

| 包 | Pending | 现状 |
|---|---|---|
| `javax.script` | 12 | SCRIPT-ENGINE：ScriptEngine API（JDK 无内置引擎） |

**一句话**：脚本引擎 API，L2 候选（需可选第三方引擎 Provider）。方向是"事件规则 DSL"。

### 2.8 `java.security.jgss` —— 16 类型（全 Excluded）

| 包 | Excluded | 现状 |
|---|---|---|
| `javax.security.auth.kerberos` | 8 | 无 Kerberos 域 |
| `org.ietf.jgss` | 8 | GSS-API 企业级 |

**一句话**：Kerberos/GSS 安全上下文，当前整模块排除。方向是"企业级票据认证"（单机无域）。

### 2.9 `java.security.sasl` —— 10 类型（全 Excluded）

| 包 | Excluded | 现状 |
|---|---|---|
| `javax.security.sasl` | 10 | SASL 网络协议认证 |

**一句话**：SASL 认证机制，当前整模块排除。方向是"网络协议握手认证"。

### 2.10 `java.sql.rowset` —— 30 类型（全 Pending）

| 包 | Pending | 现状 |
|---|---|---|
| `javax.sql.rowset` | 12 | CachedRowSet/FilteredRowSet |
| `javax.sql.rowset.serial` | 10 | SerialBlob/SerialArray |
| `javax.sql.rowset.spi` | 8 | XmlReader/XmlWriter/SyncFactory |

**一句话**：JDBC 之上的离线结果集 + 序列化 + 同步 SPI，L2 候选。方向是"查询结果物化/导出"。

### 2.11 `java.transaction.xa` —— 3 类型（全 Excluded）

| 包 | Excluded | 现状 |
|---|---|---|
| `javax.transaction.xa` | 3 | XA 分布式事务 |

**一句话**：XA 分布式事务接口，当前整模块排除。方向是"多资源管理器事务"（单机无场景）。

### 2.12 `java.xml.crypto` —— 58 类型（全 Pending）

| 包 | Pending | 现状 |
|---|---|---|
| `javax.xml.crypto` | 15 | XML 签名上下文 |
| `javax.xml.crypto.dsig` | 18 | XMLSignatureFactory |
| `javax.xml.crypto.dsig.spec` | 12 | 摘要/变换参数 |
| `javax.xml.crypto.dsig.keyinfo` | 8 | KeyInfo 解析 |
| `javax.xml.crypto.dom` | 3 | DOM 结构 |
| `javax.xml.crypto.dsig.dom` | 2 | DOM 验签上下文 |

**一句话**：XML 数字签名体系，L2 候选（XML-DSIG）。方向是"mod 清单签名验证"。

---

## 三、给未覆盖模块"想功能"时的三条提示

1. **区分 Pending 与 Excluded**：Pending（9 个模块，已设计待论证）只要补 L3 论证即可升 Planned；Excluded（`jgss`/`sasl`/`xa` 三个模块）需要**先找到真实功能**才能重开。
2. **每个功能必须回答三问**：服务玩家/运营者/开发者？类型各自承担什么职责？环境缺失如何降级？
3. **Excluded 的三个模块**（JGSS/SASL/XA）是"单机游戏 + 纯标准 API"下最难找功能的——Kerberos 需要域、SASL 需要网络协议、XA 需要分布式事务，如果坚持单机 CLI 定位，它们可能确实无场景（这不是"为覆盖而排除"，是产品边界）。

---

## 四、当前总体状态

| 项 | 值 |
|---|---|
| Planned（L3） | 1617 |
| Pending（L2 候选） | 1006 |
| Excluded | 1213 |
| 类型级 / 潜在上限 | 42.15% / 68.38% |
| 未覆盖模块 | **12 / 20**（见 §二） |
| 已覆盖模块 | 8 / 20（base/desktop/logging/management/naming/prefs/sql/xml） |
