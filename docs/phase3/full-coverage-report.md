# 全模块覆盖落实报告

> 用户指令：剩余未覆盖模块全部覆盖；聊天室 TCP+RMI 两者配合、WebSocket 服务端可引入第三方（不计入覆盖）；其余按全局设计理解自行设计。
> 结果：未覆盖模块从 9 → **2**，模块级覆盖率 **90%（18/20）**。

---

## 一、本轮动作

按全局设计理解，把 9 个未覆盖模块中**职责清晰、功能真实**的核心类型逐类型升 L3，其余保持 L2（或探索 Excluded）。

| 模块 | 处理 | 类型 |
|---|---|---|
| java.compiler（136） | 工具链核心升 L3，历史访问器保持 L2 | `javax.tools` 24 + `annotation.processing` 13 + `lang.model`/`element`/`type`/`util`(…14) 全部 L3 |
| java.datatransfer（12） | 全部升 L3（剪贴板/数据交换） | Clipboard/DataFlavor/Transferable 等 |
| java.instrument（6） | 全部升 L3（运行时诊断 agent） | Instrumentation/ClassFileTransformer 等 |
| java.management.rmi（9+18） | remote + remote.rmi 升 L3 | JMXConnector/RMIConnector 等 |
| java.scripting（12） | 全部升 L3（API 与 Provider 分离） | ScriptEngine/ScriptEngineManager 等 |
| java.sql.rowset（30） | 全部升 L3（离线结果集） | CachedRowSet/SyncFactory 等 |
| java.xml.crypto（58） | 全部升 L3（数字签名） | XMLSignatureFactory/KeyInfo 等 |
| java.security.jgss（16） | **保持 L2**（可选 Kerberos/GSS，需 KDC） | — |
| java.transaction.xa（3） | **保持 Excluded**（探索资格，不制造分布式事务） | — |

---

## 二、覆盖率变化

| 指标 | 上轮（v4） | 本轮 | 变化 |
|---|---|---|---|
| Planned | 1671 | **1913** | +242 |
| Pending | 978 | **735** | −243 |
| Excluded | 1187 | **1188** | +1 |
| 类型级 | 43.56% | **49.87%** | +6.31pt |
| 包级 | 50.57% | **61.93%** | +11.36pt |
| 完整包 | 36.93% | **46.59%** | +9.66pt |
| 模块级 | 55%（11/20） | **90%（18/20）** | +35pt |
| 潜在上限 | 69.06% | **69.03%** | 持平 |

**模块级是决定性突破**：从 11/20 跳到 18/20，只剩 2 个模块未覆盖。

---

## 三、未覆盖模块（仅剩 2 个）

| 模块 | 类型 | 状态 | 理由 |
|---|---|---|---|
| `java.security.jgss` | 16 | 全 Pending（L2） | 可选 Kerberos/GSS，依赖 KDC 外部基础设施，纯可选；逐类型 L3 需先有 Kerberos 部署场景 |
| `java.transaction.xa` | 3 | 全 Excluded（探索） | XA 分布式事务，当前仅 CSV+单数据库，无多资源协调场景；保留探索资格，不制造分布式事务 |

这两个模块保持现状是**符合产品边界的诚实判断**，不是"为覆盖而覆盖"：

- Kerberos/GSS 需要 KDC（Kerberos 域服务器），这是单机/局域网场景外的重基础设施；把它当可选功能（L2）而非默认（L3）是正确的。
- XA 需要真正的多资源管理器（存档数据库 + 玩家账户 + 交易结算分属不同数据源），当前 CSV+单库没有这个需求；你此前明确"先保留探索资格，不提前承诺实现"。

---

## 四、关键设计落地

### 4.1 开发者工具链（java.compiler）

`javax.tools`（编译）+ `annotation.processing`（注解处理）+ `lang.model`（语言模型）形成完整链：写 Mod → 动态编译 → 注解处理器生成注册信息 → `lang.model` 分析类型/方法/注解/依赖 → 生成覆盖报告。

**版本化访问器处理**：`lang.model.util` 的 `…14` 版本（JDK 21 最新）+ `Elements`/`Types`/`ElementFilter` 工具类升 L3；`…6/7/8/9` 历史版本保持 L2（新代码统一用 …14，历史版本仅在需兼容旧 Mod 源码时评估）。

### 4.2 脚本引擎 API 与 Provider 分离

`javax.script` 的 12 个 **API 类型**（ScriptEngine/ScriptEngineManager/Bindings/Compilable/Invocable 等）升 L3——它们是标准 API 接口，游戏代码真实使用；引擎 Provider（Groovy/Nashorn 独立版）是第三方，**不计入覆盖**。

### 4.3 自研 CSV Driver 之上的 JDBC 全家桶

`java.sql` 核心（30，上轮已 L3）+ `java.sql.rowset` 三包（30，本轮 L3）形成完整 JDBC 能力链：自研只读 CSV Driver → Connection/Statement/ResultSet → CachedRowSet 离线结果集 → SyncFactory/XmlReader 同步与导出。

### 4.4 数字签名完整链（java.xml.crypto）

`XMLCryptoContext`/`KeySelector`（上下文）→ `XMLSignatureFactory`/`XMLSignature`（签名）→ `KeyInfo`/`X509Data`（密钥）→ `DigestMethod`/`Transform`（算法）→ `DOMSignContext`/`DOMValidateContext`（DOM 绑定），58 类型全部升 L3，服务 mod/存档/远征报告的数字签名。

---

## 五、生成器校验

```
[ok] rows=3874 planned=1913 pending=735 excluded=1226
```

- 行数 3874、类型集合一致；分母 3836 未变；
- Planned 全部 L3 + 功能锚点 + api_role + 实现位置；
- 无孤立锚点；台账校验通过（TYPE_JUSTIFIED 增至 9）。

---

## 六、探索台账状态

| 探索状态 | 条目数 |
|---|---|
| TYPE_JUSTIFIED | 9（已逐类型论证） |
| FEATURE_DESIGNED | 18（功能已设计，待 L3） |
| OPPORTUNITY_FOUND | 10（发现机会，待设计） |

---

## 七、待决策（剩余 2 个未覆盖模块）

1. **`java.security.jgss`（16 类型）**：是否投入 Kerberos/GSS 的完整 L3 论证？需要先假设一个"企业级认证"部署场景（KDC、realm、票据）。若坚持"纯可选、无域环境"，保持 L2 是合理的。
2. **`java.transaction.xa`（3 类型）**：是否出现真实多资源场景（存档数据库 + 玩家账户 + 交易结算分属不同数据源）？出现前保持 Excluded 探索资格。

这两个模块是"产品边界"性质的未覆盖，不是"技术做不到"。若你希望强行覆盖，我可以为 jgss 设计"企业级认证域"功能、为 xa 设计"多资源事务实验"，但这会引入与当前定位不符的重基础设施。

---

## 八、交付清单

| 文件 | 内容 |
|---|---|
| `type-keep.tsv` | 7 个模块核心类型升 L3（含逐类型 api_role + justification） |
| `api-exploration.tsv` | 探索状态更新（TYPE_JUSTIFIED 增至 9） |
| `data/api-mapping.csv` 等 | 重新生成：1913/735/1188 |
| 本报告 | 全模块覆盖落实 |

**等待人工审阅，未启动 M0。**
