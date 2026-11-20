# 阶段三 v3 映射统计摘要（自动生成）

> 项目目标：**API 覆盖优先，游戏作为承载载体**。准入：Planned 必须 L3 逐类型论证。
> 分母：`coverage_scope=IN_DENOMINATOR`（类型 3836 / 包 176 / 模块 20）。

## 1. 行数与状态

| 项 | 数量 |
|---|---|
| 冻结清单总行数 | 3874 |
| 其中 Preview | 38 |
| 分母行 | 3836 |
| Planned（L3 已论证） | 1913 |
| Pending（L1/L2 待论证） | 735 |
| Excluded | 1188 |
| 合计 | 3836 = 3836 |

## 2. 覆盖率（口径：Planned）

| 指标 | 数值 |
|---|---|
| 类型级 | 49.87%（1913/3836） |
| 包级 | 61.93%（109/176） |
| 完整包 | 48.86%（86/176） |
| 模块级 | 90.00%（18/20） |

> Planned 是**规划覆盖**，最终以阶段六 Verified 为准。

## 3. 论证层级分布

| 层级 | 数量 |
|---|---|
| L1 | 608 |
| L2 | 735 |
| L3 | 2493 |

## 4. 功能来源分布（feature_origin）

| 来源 | 数量 |
|---|---|
| API_DRIVEN | 1292 |
| CORE | 807 |
| DEVELOPER_TOOLING | 184 |
| INFRASTRUCTURE | 365 |

## 5. 锚点类型分布（anchor_type）

| 锚点 | 数量 |
|---|---|
| DEVELOPER | 376 |
| OPERATOR | 968 |
| PLAYER | 1304 |

## 6. 排除原因分布

| 原因码 | 数量 |
|---|---|
| BIZ | 1134 |
| DEPRECATED | 54 |

## 7. 批次分布（Planned）

| 批次 | 类型数 |
|---|---|
| M0 | 468 |
| M1 | 167 |
| M2 | 160 |
| M3 | 9 |
| M5 | 875 |
| M6 | 234 |

## 8. 功能锚点分布（Planned/Pending）

| feature_id | 名称 | 来源 | 锚点 | 类型数 |
|---|---|---|---|---|
| ADMIN-DESKTOP | - | - | - | 112 |
| AGENT-INSTR | 运行时增强（Instrumentation） | DEVELOPER_TOOLING | DEVELOPER | 6 |
| AUDIO-AMBIENT | 氛围乐 | API_DRIVEN | PLAYER | 14 |
| AUDIO-CUE | 事件音效 | API_DRIVEN | PLAYER | 25 |
| AUTH | - | - | - | 36 |
| CERT-TRUST | - | - | - | 52 |
| CHAT | 星际远征通信频道（聊天室） | API_DRIVEN | PLAYER | 48 |
| CLIPBOARD-EXPORT | 剪贴板导出 | API_DRIVEN | PLAYER | 12 |
| CORE-CONFIG | 配置装载与内省 | INFRASTRUCTURE | OPERATOR | 148 |
| CORE-HISTORY | 编年史查询与导出 | CORE | PLAYER | 38 |
| CORE-IO | 存档与完整性 | CORE | PLAYER | 230 |
| CORE-MONEY | 货币与守恒结算 | CORE | PLAYER | 4 |
| CORE-SIM | 确定性 tick 管线 | CORE | OPERATOR | 132 |
| CORE-TEST | 自研测试器 | DEVELOPER_TOOLING | DEVELOPER | 46 |
| CORE-TEXT | CLI 文本呈现 | CORE | PLAYER | 44 |
| CORE-WORLD | 世界状态与规则基座 | CORE | PLAYER | 359 |
| HTTP-TELEMETRY | - | - | - | 2 |
| JDBC-QUERY | 编年史 SQL 查询 | API_DRIVEN | OPERATOR | 103 |
| JNDI-LOOKUP | 命名与服务发现 | API_DRIVEN | DEVELOPER | 82 |
| KERBEROS-AUTH | - | - | - | 16 |
| LOG-RING | 环形缓冲与轮转日志 | INFRASTRUCTURE | OPERATOR | 16 |
| MAP-RENDER | 星图与报表渲染 | API_DRIVEN | PLAYER | 212 |
| MOD-LOADER | 模组装载 | API_DRIVEN | PLAYER | 7 |
| OBS-JMX | 运行观测（JMX） | API_DRIVEN | OPERATOR | 125 |
| PREF-STORE | 用户偏好存储 | INFRASTRUCTURE | OPERATOR | 9 |
| PRINT-EXPORT | - | - | - | 135 |
| PROC-ANNOT | 注解处理与语言模型 | DEVELOPER_TOOLING | DEVELOPER | 112 |
| REMOTE-AUTH | 联机可插拔认证（SASL） | API_DRIVEN | OPERATOR | 10 |
| REMOTE-JMX | 远程管理端点 | API_DRIVEN | OPERATOR | 27 |
| REMOTE-WATCH | - | - | - | 113 |
| RES-URI | 资源与导出路径标识 | INFRASTRUCTURE | PLAYER | 6 |
| RULE-COMPILE | 玩家规则编译 | API_DRIVEN | PLAYER | 37 |
| RUNTIME-JLINK | 运行时装配与镜像 | DEVELOPER_TOOLING | DEVELOPER | 20 |
| SAVE-CRYPTO | 存档加密与完整性 | INFRASTRUCTURE | OPERATOR | 186 |
| SCRIPT-ENGINE | 脚本规则引擎 | API_DRIVEN | PLAYER | 12 |
| TLS-CHANNEL | - | - | - | 45 |
| UNDO-PLAN | 经营指令撤销 | API_DRIVEN | PLAYER | 9 |
| XML-DSIG | XML 签名验证 | API_DRIVEN | DEVELOPER | 58 |

## 9. JDK 模块级明细

| 模块 | 分母 | Planned | Pending | Excluded | 类型级 |
|---|---|---|---|---|---|
| java.base | 1353 | 1032 | 254 | 67 | 76.27% |
| java.compiler | 136 | 99 | 36 | 1 | 72.79% |
| java.datatransfer | 12 | 12 | 0 | 0 | 100.00% |
| java.desktop | 1524 | 266 | 254 | 1004 | 17.45% |
| java.instrument | 6 | 6 | 0 | 0 | 100.00% |
| java.logging | 17 | 16 | 0 | 1 | 94.12% |
| java.management | 195 | 79 | 64 | 52 | 40.51% |
| java.management.rmi | 9 | 7 | 2 | 0 | 77.78% |
| java.naming | 102 | 30 | 47 | 25 | 29.41% |
| java.net.http | 22 | 20 | 2 | 0 | 90.91% |
| java.prefs | 9 | 9 | 0 | 0 | 100.00% |
| java.rmi | 55 | 28 | 13 | 14 | 50.91% |
| java.scripting | 12 | 12 | 0 | 0 | 100.00% |
| java.security.jgss | 16 | 0 | 16 | 0 | 0.00% |
| java.security.sasl | 10 | 6 | 4 | 0 | 60.00% |
| java.sql | 74 | 30 | 43 | 1 | 40.54% |
| java.sql.rowset | 30 | 30 | 0 | 0 | 100.00% |
| java.transaction.xa | 3 | 0 | 0 | 3 | 0.00% |
| java.xml | 193 | 173 | 0 | 20 | 89.64% |
| java.xml.crypto | 58 | 58 | 0 | 0 | 100.00% |

## 10. 校验结论

- 输出行数与冻结清单一致；类型集合一致。
- 状态三态：Planned / Pending / Excluded。
- Planned 全部 L3 论证 + 功能锚点 + api_role + 实现位置。
- Pending 记录未决问题；Excluded 标注 API 驱动探索状态。
- 功能锚点双向完整：无孤立锚点。
- 分母 3836 未变，覆盖率变化全部来自逐类型状态调整。

## 11. API 探索台账（api-exploration.tsv）

| 探索状态 | 台账条目数 |
|---|---|
| FEATURE_DESIGNED | 18 |
| OPPORTUNITY_FOUND | 10 |
| TYPE_JUSTIFIED | 9 |
| UNEXPLORED | 19 |

> `UNEXPLORED`=因废弃或确实无场景，未做 API 驱动探索；`OPPORTUNITY_FOUND`=已发现机会待设计；`FEATURE_DESIGNED`=功能已设计（L2 候选）；`TYPE_JUSTIFIED`=已逐类型论证（L3）。
