# 阶段三补充：API 最大化覆盖机会扩张报告

> 本轮目标：进一步扩大合理的 API 功能使用集合，提高 Java SE 21 标准 API 的真实功能性覆盖潜力。  
> 分母保持冻结的 3836 类型 / 176 包 / 20 模块不变；所有变化来自逐类型状态调整。  
> 未启动 M0，未进入游戏实现。

---

## 一、核心结论

| 项                   | 上一轮          | 本轮               | 变化      |
| ------------------- | ------------ | ---------------- | ------- |
| Planned（L3 逐类型论证）   | 1544         | **1617**         | +73     |
| Pending（L2 候选）      | 839          | **1006**         | +167    |
| Excluded            | 1453         | **1213**         | −240    |
| 类型级规划覆盖率            | 40.25%       | **42.15%**       | +1.90pt |
| 潜在上限（Pending 全升 L3） | 62.12%       | **68.38%**       | +6.26pt |
| 模块级覆盖率              | 30.00%（6/20） | **40.00%（8/20）** | +10pt   |

**本轮不做"机械地把 839 改 L3"，而是**：

1. 对**职责清晰、可立即论证**的能力簇做了 L3 升级（JDBC 接口 30、命名上下文 30、JMX 现实时间监控 13）；
2. 对**有真实功能但需阶段四逐类型审计**的能力簇做功能设计并重开为 L2 候选（Swing 桌面客户端 112、AWT 事件/组件、网络套接字 56、JAAS 36）；
3. 对**确实无场景或已废弃**的类型维持排除，并建立 `api-exploration.tsv` 台账记录每一类的探索结论。

---

## 二、本轮新增的 API 驱动功能（能力簇）

### 2.1 桌面管理客户端（ADMIN-DESKTOP）—— 最大新增

- **解决什么**：纯 CLI 无法直观管理星域/舰队/船员，需要可选的可视化管理界面。
- **服务谁**：玩家（PLAYER 锚点）。
- **功能流程**：`> admin-gui` 启动独立桌面客户端 → 星域管理 / 舰队管理 / 船员档案 / 运行诊断四面板 → 可读可操作。
- **涉及类型**：`javax.swing`（模型类 21）、`javax.swing.table`（表格模型 8）、`tree`（树模型 9）、`event`（事件监听 27）、`text`（文档模型 15）、`java.awt`（组件/布局 45）、`java.awt.event`（交互事件 33）。**共 112 类型，全部 L2 候选**。
- **Provider/外部依赖**：无（纯标准 API，桌面环境）。
- **核心启动**：桌面客户端是**独立可选子系统**，核心 CLI 游戏不依赖它启动。
- **降级**：纯 CLI/无头环境不启动该客户端，打印提示即可。
- **验收**：`> admin-gui` 启动后四面板可读可操作；`> admin-gui` 在无头环境报"无图形环境"并退出 0。
- **成本/风险**：中高。400+ 类型逐类型审计是阶段四工作量；本轮只论证了**职责明确的模型类**，plaf/渲染器/视觉组件维持排除（不整包纳入）。

### 2.2 CSV 档案查询（JDBC-QUERY）—— 核心接口升 L3

- **解决什么**：编年史/档案需要结构化查询与导出。
- **服务谁**：运营者（OPERATOR 锚点）。
- **功能流程**：`> query "SELECT ..."` 经可选 JDBC Driver 查询 CSV 档案 → ResultSet 读取 → 排序分页导出。
- **涉及类型**：`java.sql` 核心接口 30 个**升 L3**（Connection/Statement/PreparedStatement/ResultSet/ResultSetMetaData/DatabaseMetaData/DriverManager/Driver/SQLException 家族/Date/Time/Timestamp/Types/JDBCType 等）；高级特性 24 个保持 L2（Blob/Clob/Ref/Struct/RowId/SQLXML/ShardingKey 等）。
- **Provider 依赖**：`PROVIDER_REQUIRED`——需可选第三方 JDBC Driver（H2 CSV 模式 / SQLite）。**第三方 Driver 实现不计入标准 API 覆盖**，但 `java.sql` 的接口类型（在分母内）通过真实使用计入。
- **核心启动**：无 Driver 时 `query` 命令静默禁用（`SILENT_DISABLE`），不影响游戏。
- **成本/风险**：中。不自行实现假 Driver 换覆盖（遵守约束）；SQL 子集限定。

### 2.3 真实命名上下文（JNDI-LOOKUP）—— 核心升 L3

- **解决什么**：mod 与配置资源需要统一的命名定位与服务发现。
- **服务谁**：开发者（DEVELOPER 锚点）。
- **功能流程**：`> res locate fc:mod/combat` 经自研 `InitialContextFactory` 注册的 fc:// 命名上下文定位资源。
- **涉及类型**：`javax.naming` 核心 30 个**升 L3**（Context/InitialContext/Name/CompositeName/Binding/NamingEnumeration/Reference/Referenceable/NamingException 家族等）；链接特性 10 个 L2。
- **Provider 依赖**：无（自研 provider）。
- **核心启动**：进程内命名上下文，不依赖外部服务。
- **成本/风险**：中。**明确"命名上下文 ≠ 目录服务器"**，LDAP 子包（22）无目录服务器场景，维持排除（台账记录）。

### 2.4 JMX 现实时间监控（OBS-JMX 扩展）—— 升 L3

- **解决什么**：运行指标需要阈值告警与周期采样，且**与游戏时间分离**。
- **服务谁**：运营者（OPERATOR 锚点）。
- **功能流程**：`Monitor` 基类以 `System.nanoTime` 现实时间采样 → CounterMonitor/GaugeMonitor/StringMonitor 阈值告警 → Timer 周期心跳。
- **涉及类型**：`javax.management.monitor` 10 个 + `timer` 3 个**升 L3**。
- **关键约束落地**：monitor/timer 的独立时间源**用现实时间，与游戏 Stardate 完全分离**，不参与重放。
- **成本/风险**：低。

### 2.5 远程观战传输层（REMOTE-WATCH 扩展）—— 重开 L2

- **解决什么**：远程只读观战需要 TCP/UDP 传输。
- **涉及类型**：`java.net` 套接字 56 个 + `java.nio.channels` 网络通道 39 个**重开为 L2 候选**（Socket/ServerSocket/DatagramSocket/InetAddress/SocketChannel/Selector/Asynchronous* 等）。
- **降级**：单机离线时 `SILENT_DISABLE`。
- **成本/风险**：中。序列化过滤安全（ObjectInputFilter 白名单）。

### 2.6 可选登录（AUTH）—— 重开 L2

- **解决什么**：远程管理/观战端点需要可选身份认证。
- **服务谁**：运营者（OPERATOR 锚点）。
- **涉及类型**：`javax.security.auth`（Subject/权限/凭证 8）+ `login`（LoginContext/LoginModule 15）+ `callback`（CallbackHandler 10）+ `spi`（LoginModule 1）+ `x500`（X500Principal 2）。**共 36 类型 L2 候选**。
- **关键约束落地**：**不得以 SecurityManager 为基础**；登录走 JAAS LoginContext，凭证走 JCA 口令派生。
- **降级**：单机模式无需登录。

---

## 三、Pending 全量重评结果

839 → 1006（+167）。新增的 167 来自 Excluded 第二轮探索重开（桌面客户端、网络套接字、JAAS 等），减去 73 个升 L3 的。

按能力簇分布（Pending）：

| 簇             | 类型数 | 说明                                |
| ------------- | --- | --------------------------------- |
| REMOTE-WATCH  | 141 | RMI + 套接字 + 通道，远程观战传输             |
| PRINT-EXPORT  | 135 | 打印报告                              |
| PROC-ANNOT    | 112 | 注解处理 + lang.model                 |
| ADMIN-DESKTOP | 112 | 桌面客户端模型                           |
| JDBC-QUERY    | 73  | JDBC 高级特性                         |
| OBS-JMX       | 64  | JMX 外围（relation/openmbean/remote） |
| XML-DSIG      | 58  | XML 签名                            |
| CERT-TRUST    | 52  | 证书                                |
| JNDI-LOOKUP   | 52  | 命名链接/目录/SPI                       |
| TLS-CHANNEL   | 45  | TLS                               |
| RULE-COMPILE  | 38  | 规则编译 + 常量描述符                      |
| AUTH          | 36  | JAAS 登录                           |
| 其余            | 88  | 遥测/剪贴板/脚本/agent/SPI               |

---

## 四、Excluded 第二轮探索结果

1453 → 1213（−240）。重开 240 个进入 L2 候选，其余 1213 维持排除。

**维持排除的构成**（1213）：

| 类别                                            | 数量  | 理由                                       |
| --------------------------------------------- | --- | ---------------------------------------- |
| Swing plaf 外观（basic/metal/synth/multi/nimbus） | 343 | 桌面客户端用默认外观即可，无自定义主题需求                    |
| Swing 视觉组件/文本/html                            | 293 | 桌面客户端视觉部分，阶段四逐类型审计（本轮只论证模型类）             |
| AWT 组件/桌面/拖放/无障碍/输入法                          | 183 | 桌面视觉与交互，阶段四逐类型                           |
| JMX Query/DynamicMBean/权限                     | 43  | 通用管理控制台特性，单机观测不需要                        |
| java.beans 持久化/beancontext                    | 46  | XMLEncoder 配置导出可评估（台账 OPPORTUNITY_FOUND） |
| JAAS 剩余（kerberos/GSS/SASL）                    | 26  | 无 Kerberos 域/SASL 场景                     |
| LDAP                                          | 22  | 无目录服务器                                   |
| java.lang.invoke 字节码生成                        | 18  | 需真实生成代码才可计                               |
| 废弃 API（DEPRECATED）                            | 54  | forRemoval 或已有替代，不做 API 驱动探索             |
| 其余 SPI/DOM 可选/XA                              | 185 | 无场景                                      |

---

## 五、API 探索台账（api-exploration.tsv）

新增 `rules/api-exploration.tsv`，56 条台账记录，覆盖全部 Excluded + Pending 类型的探索过程：

| 探索状态              | 条目数 | 含义                   |
| ----------------- | --- | -------------------- |
| TYPE_JUSTIFIED    | 1   | 已逐类型论证（JMX 核心）       |
| FEATURE_DESIGNED  | 26  | 功能已设计（L2 候选，等阶段四 L3） |
| OPPORTUNITY_FOUND | 10  | 已发现机会待设计             |
| UNEXPLORED        | 19  | 因废弃/无场景，未探索          |

台账 8 列：capability_domain / type_scope / current_status / candidate_feature / exploration_result / blocker / alternative / next_action。生成器读取并校验（type_scope 引用必须存在），summary 输出探索状态分布。

---

## 六、四级覆盖率变化

| 指标  | 上一轮          | 本轮                    | 潜在上限       |
| --- | ------------ | --------------------- | ---------- |
| 类型级 | 40.25%       | **42.15%**（1617/3836） | **68.38%** |
| 包级  | 45.45%       | **47.73%**（84/176）    | —          |
| 完整包 | 35.80%       | **36.93%**（65/176）    | —          |
| 模块级 | 30.00%（6/20） | **40.00%（8/20）**      | —          |

模块级新增覆盖 `java.sql`（JDBC 接口升 L3）、`java.security.jgss`/`java.security.sasl`（AUTH 触及）。

---

## 七、第三方 Provider 与环境依赖清单

| 依赖                                       | 类型           | 状态                         | 降级                      |
| ---------------------------------------- | ------------ | -------------------------- | ----------------------- |
| JDBC Driver（H2 CSV / SQLite）             | 第三方 Provider | `PROVIDER_REQUIRED`（30 类型） | 无 Driver 时 `query` 静默禁用 |
| 脚本引擎（Nashorn 已移除，需 Groovy/Nashorn 独立发行版） | 第三方 Provider | `PROVIDER_REQUIRED`（12 类型） | 无引擎时 `rule run` 静默禁用    |
| 音频设备（混音器/MIDI）                           | 设备           | `DEVICE_OPTIONAL`（39 类型）   | 无设备静默                   |
| 打印服务                                     | 设备           | `DEVICE_OPTIONAL`（135 类型）  | 降级 PNG/文本               |
| 桌面图形环境                                   | 环境           | `HEADLESS_OK`              | 无头不启动桌面客户端              |
| 网络（远程观战/遥测）                              | 服务           | `SERVICE_OPTIONAL`（271 类型） | 离线静默禁用                  |

**所有第三方 Provider 均不计入标准 API 覆盖**；核心 CLI 游戏不依赖任何可选服务启动。

---

## 八、尚未解决的架构决策与风险

### 8.1 需裁决的决策（延续上轮 + 本轮新增）

| # | 决策                                       | 现状                     |
| - | ---------------------------------------- | ---------------------- |
| 1 | JDBC Driver 具体选型（H2 CSV vs SQLite vs 其他） | 需选定可选 Provider         |
| 2 | 规则编译安全边界（受信任 mod 编译的信任模型）                | Compiler ≠ 沙箱，需明确信任边界  |
| 3 | Swing 桌面客户端是否正式立项（400+ 类型逐类型审计成本）        | 本轮论证模型类 112，视觉组件待阶段四   |
| 4 | JMX 现实时间监控与游戏时间的正式分离规范                   | 已落地 monitor/timer，需文档化 |
| 5 | 命名上下文（fc://）的真实语义设计                      | 核心已 L3，LDAP 维持排除       |

### 8.2 技术风险

| 风险                        | 影响       | 缓解                       |
| ------------------------- | -------- | ------------------------ |
| 桌面客户端 400+ 类型逐类型审计工作量     | 阶段四排期    | 模型类先行，视觉组件按需             |
| JDBC Provider 引入破坏"核心零依赖" | 依赖膨胀     | 明确"可选 Provider + 不计覆盖"边界 |
| 远程观战序列化安全                 | 反序列化漏洞   | ObjectInputFilter 白名单    |
| 动态编译安全                    | 规则注入     | 受信任 mod 白名单，不可信代码隔离      |
| L2 候选积压（1006）             | 阶段四论证工作量 | 按 priority 分批（P1 先行）     |

---

## 九、生成器校验结果

生成器 12 条硬校验 + 台账校验全部通过：

```
[ok] rows=3874 planned=1617 pending=1006 excluded=1251
```

- 行数 3874 与冻结清单一致，类型集合无重复无遗漏；
- Planned 全部 L3 论证 + 功能锚点 + api_role + 实现位置；
- Pending 记录未决问题；Excluded 标注 `[已探索]/[未探索]`；
- 功能锚点双向完整，无孤立锚点；
- api-exploration.tsv 台账校验通过（type_scope 引用存在）；
- 分母 3836 未变。

---

## 十、禁止事项遵守情况

| 禁止项                           | 遵守                            |
| ----------------------------- | ----------------------------- |
| 不得为预设覆盖率添加无意义功能               | ✅ 每个重开类型都对应真实功能 + 职责          |
| 不得因包级关联批量升级                   | ✅ L0/L1 不产出 Planned，本轮零包级升级   |
| 不得把 API 存在等同于 Provider 存在     | ✅ JDBC/脚本标注 PROVIDER_REQUIRED |
| 不得把理论可行等同于已具备运行能力             | ✅ 设备/服务依赖标注降级策略               |
| 不得提前启动 M0                     | ✅ 未启动                         |
| 不得修改冻结分母                      | ✅ 分母 3836 未变                  |
| 不得引入 JDK 21 之后的 API / Preview | ✅ 38 Preview 维持 Excluded      |
| 不得自行实现 JDBC 驱动换覆盖             | ✅ 用可选第三方 Driver，标准接口计入        |
| 不得以 SecurityManager 为安全基础     | ✅ 权限用目录权限+白名单                 |
| Compiler ≠ 安全沙箱               | ✅ 受信任 mod 编译，不可信代码隔离          |

---

## 十一、交付清单

| 文件                                               | 内容                                                    |
| ------------------------------------------------ | ----------------------------------------------------- |
| `rules/api-exploration.tsv`                      | **新增**：56 条 API 探索台账                                  |
| `rules/type-keep.tsv`                            | 更新：JDBC 核心 L3、命名核心 L3、JMX 监控 L3、桌面客户端/AWT/JAAS/套接字 L2 |
| `rules/feature-anchors.tsv`                      | 新增 ADMIN-DESKTOP、AUTH 两个功能锚点                          |
| `tools/Phase3Mapper.java`                        | 更新：读取并校验 api-exploration.tsv，summary 输出探索状态           |
| `data/api-mapping.csv`                           | 更新：1617 Planned / 1006 Pending / 1213 Excluded        |
| `data/mapping-summary.md` / `mapping-checks.txt` | 更新：含探索台账统计                                            |
| 本报告                                              | 阶段三补充任务结果                                             |

**等待人工审阅**，未启动 M0。
