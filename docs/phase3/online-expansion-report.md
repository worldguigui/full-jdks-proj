# 项目定位升级 + 12 模块采纳 + 联机系统设计报告

> 本轮：项目定位升级为「Full JDK 21 Project」，12 个模块全部进入设计范围，多人联机（聊天室）正式进路线图。
> 核心原则（用户强调）：**模块采纳 ≠ 功能锚点成立 ≠ 类型达到 L3**，三者严格区分。
> 未启动 M0。

---

## 一、项目定位升级

项目定位由「单机星际经营游戏」正式升级为：

> **Full JDK 21 Project —— 基于 Java SE 21 的可扩展星际模拟与联机管理平台。**

核心游戏独立运行，外围服务按需启用。升级带来三个结果（已写入 `project-direction.md` v4）：

1. 原本被单机 CLI 边界排除的网络/安全/远程管理 API 有了真实场景；
2. 游戏不再是唯一 API 载体，开发者工具和管理工具也是正式产品组成；
3. 有了单机→多人协作的演进路径。

**新增安全边界**（本地可信 ≠ 远程可信）：本地玩家/Mod/工具充分权限；远程普通玩家仅授权接口；远程管理员明确授权；远程 Mod 不自动获服务器本地权限。

---

## 二、12 个模块全部采纳（模块采纳 ≠ 类型 L3）

| 模块 | 采纳 | 落地功能 | 类型状态 |
|---|---|---|---|
| java.compiler | ✅ | 规则编译、Mod 编译、注解处理、覆盖审计（开发者工具链） | 全 Pending（L2） |
| java.datatransfer | ✅ | 桌面客户端剪贴板、数据交换 | 全 Pending（L2） |
| java.instrument | ✅ | Agent 运行时诊断、类加载审计、性能分析 | 全 Pending（L2） |
| java.management.rmi | ✅ | 远程 JMX 管理 | 全 Pending（L2） |
| java.net.http | ✅ | WebSocket 客户端、HTTP 通信、遥测 | **20 升 L3**（CHAT） |
| java.rmi | ✅ | 远程调用、联机服务、远程观战 | **28 升 L3**（CHAT） |
| java.scripting | ✅ | 可选脚本引擎、事件规则脚本 | 全 Pending（L2） |
| java.security.jgss | ✅ | 可选 Kerberos/GSSAPI 认证 | 全 Pending（KERBEROS-AUTH L2） |
| java.security.sasl | ✅ | 聊天服务器可插拔认证 | **6 升 L3**（REMOTE-AUTH）+ 4 L2 |
| java.sql.rowset | ✅ | 离线结果集、数据交换、查询缓存 | 全 Pending（L2） |
| java.transaction.xa | ✅（探索） | 多资源事务协调实验 | Excluded（探索资格，不承诺实现） |
| java.xml.crypto | ✅ | Mod/存档/远征报告数字签名 | 全 Pending（L2） |

**关键区分**：采纳的是"模块进入设计范围"；每个类型仍需逐一 L3 论证。本轮只有**职责清晰、联机场景直接用到**的核心类型升了 L3，其余保持 L2 候选。

---

## 三、联机通信系统（第一阶段：聊天室）

新增 `docs/phase3/online-system-design.md`。三阶段路线：

1. **第一阶段（聊天室）**：身份/频道/广播/私聊/成员列表/历史/存储/管理 —— 通信基础设施。
2. **第二阶段（远征协同）**：分享坐标/舰队状态/任务/报告、观战、远程状态。
3. **第三阶段（多人世界）**：后续方向，暂不做（世界状态所有权/并发/同步复杂）。

**通信技术选型**（关键判断：`java.net.http` WebSocket 只提供客户端，不带服务端）：

| 通道 | 角色 |
|---|---|
| TCP Socket + RMI | 核心联机能力（聊天室承载） |
| WebSocket（java.net.http 客户端） | 可选客户端接入（服务端可第三方） |
| TLS | 加密链路 |
| SASL | 可插拔认证握手 |
| Kerberos/GSS | 可选企业级认证（需 KDC） |

**不做的事**：不为覆盖 API 让所有通信方式同时承担同一功能。

---

## 四、四个模块重新审查结果

| 模块 | 重审前 | 重审后 | 变化 |
|---|---|---|---|
| java.rmi | 55 全 Pending/Excluded | **28 Planned** + 13 Pending + 14 Excluded（废弃） | Remote/UnicastRemoteObject/Registry/RemoteException 家族升 L3 |
| java.net.http | 22 全 Pending | **20 Planned** + 2 Pending | HttpClient/HttpRequest/WebSocket 升 L3 |
| java.security.sasl | 10 全 Excluded | **6 Planned** + 4 Pending | Sasl/SaslServer/SaslClient 升 L3 |
| java.security.jgss | 16 全 Excluded | 16 Pending | 重开为 KERBEROS-AUTH（可选 L2） |

**java.rmi 核心升 L3 的类型**：`Remote`/`RemoteException`/`Naming`/`UnicastRemoteObject`/`RemoteObject`/`RemoteServer`/`LocateRegistry`/`Registry`/`RMISocketFactory` 家族 + 完整异常家族 —— 这些是聊天室"远程对象接口 + 注册发现 + 传输工厂"的职责链。

**sasl 核心升 L3**：`Sasl`/`SaslServer`/`SaslClient`/`SaslException`/`AuthenticationException`/`AuthorizeCallback` —— 聊天室认证握手。

---

## 五、覆盖率变化

| 指标 | 上轮 | 本轮 | 变化 |
|---|---|---|---|
| Planned | 1617 | **1671** | +54 |
| Pending | 1006 | **978** | −28 |
| Excluded | 1453→1213→ | **1187** | −26 |
| 类型级 | 42.15% | **43.56%** | +1.41pt |
| 包级 | 47.73% | **50.57%** | +2.84pt |
| 模块级 | 40%（8/20） | **55%（11/20）** | +15pt |
| 潜在上限 | 68.38% | **69.06%** | +0.68pt |

**模块级是最大变化**：联机系统让 `java.rmi`、`java.net.http`、`java.security.sasl` 三个模块从"未覆盖"变为"已覆盖"，模块级从 8/20 跳到 11/20。

---

## 六、未覆盖模块（从 12 → 9）

| 模块 | 类型 | 状态 | 已设计的功能 |
|---|---|---|---|
| java.compiler | 136 | Pending | 规则/Mod 编译 + 注解处理 + 覆盖审计 |
| java.datatransfer | 12 | Pending | 剪贴板/数据交换 |
| java.instrument | 6 | Pending | Agent 运行时诊断 |
| java.management.rmi | 9 | Pending | 远程 JMX |
| java.scripting | 12 | Pending | 可选脚本引擎 |
| java.security.jgss | 16 | Pending | 可选 Kerberos/GSS |
| java.sql.rowset | 30 | Pending | 离线结果集/数据交换 |
| java.transaction.xa | 3 | Excluded（探索） | 多资源事务（不承诺） |
| java.xml.crypto | 58 | Pending | 数字签名 |

这 9 个模块**全部已进入设计范围**（有 feature 锚点 + 类型清单），只是尚未逐类型 L3 论证——这正是"模块采纳 ≠ 类型 L3"的体现。`java.transaction.xa` 保持 Excluded 但标记"探索资格"，不制造分布式事务。

---

## 七、java.compiler 的开发者工具链定位（用户特别强调）

不做"单独规则编译器"，而是**完整开发者工具链**：

1. 用户编写 Java Mod；
2. `javax.tools` 动态编译；
3. 注解处理器生成 Mod 注册信息；
4. `javax.lang.model` 分析 Mod 的类型/方法/注解/依赖；
5. 开发工具检查 Mod 是否符合扩展规范；
6. 生成 API 使用报告，辅助覆盖审计。

编译器、注解处理器、语言模型形成相互关联的实际工作流（归属 PROC-ANNOT + RULE-COMPILE 两个 feature，全 L2 候选待阶段四 L3）。

---

## 八、生成器校验

```
[ok] rows=3874 planned=1671 pending=978 excluded=1225
```

- 行数 3874、类型集合一致；分母 3836 未变；
- Planned 全部 L3 + 锚点 + api_role + 位置；
- 无孤立锚点；台账校验通过。

---

## 九、仍待决策

1. **联机第一阶段（聊天室）的传输选型**：TCP Socket vs RMI 作为主通道（本设计倾向两者配合，Socket 承载连接 + RMI 承载对象调用）。
2. **WebSocket 服务端**是否引入可选第三方实现（客户端用标准 `java.net.http`，服务端第三方不计覆盖）。
3. **java.transaction.xa** 保持探索资格，等真实多资源场景（存档数据库/玩家账户/交易结算）出现再启用。
4. **Kerberos/GSS** 是否作为默认还是纯可选（需 KDC，倾向纯可选 + 降级 SASL/明文）。

---

## 十、交付清单

| 文件 | 内容 |
|---|---|
| `project-direction.md` | v4 升级：定位 + 安全边界 + 设计原则 9 条 |
| `online-system-design.md` | **新增**：联机通信系统设计（三阶段 + 技术选型 + API 映射） |
| `feature-anchors.tsv` | 新增 CHAT / REMOTE-AUTH / KERBEROS-AUTH |
| `type-keep.tsv` | RMI/HTTP/SASL 核心升 L3，JGSS 重开 L2 |
| `api-exploration.tsv` | 更新 GSS/SASL/Kerberos/XA 探索状态 |
| `package-rules.tsv` | 补 SASL/GSS/Kerberos 的 fc 归属 |
| `data/api-mapping.csv` 等 | 重新生成：1671/978/1187 |
| 本报告 | 升级 + 12 模块 + 联机设计结果 |

**等待人工审阅，未启动 M0。**
