# 运行时诊断、认证与离线数据工作台

本文件说明 v0.10.0 新增的三组可选能力。它们都不参与核心单机游戏的必需启动路径：
不加载 Agent、没有 Kerberos/SASL 环境、或运行在不支持 RowSet 的 JDK 上时，
游戏仍按原方式运行，相应命令会明确报告降级原因。

## 1. 运行时诊断 Agent（`java.instrument`）

### 启动

```text
java -javaagent:fc-agent/target/fc-agent-0.1.0-SNAPSHOT-agent.jar=limit=500 \
     -jar fc-runtime/target/frontier-chronicle.jar
```

- Agent JAR 由 `fc-agent` 模块生成，`Premain-Class=fc.agent.FcAgent`，
  `Can-Redefine-Classes`/`Can-Retransform-Classes` 为 true。
- `limit` 控制加载类名样本上限（默认 300）。
- Agent 只注册观察型 `ClassFileTransformer`（始终返回 `null`），**不修改任何字节码**，
  也不提供运行时 Attach；没有 `agentmain`。

### 命令

```text
diagnostics                     # 总数、转换次数、采样时间
diagnostics classes [前缀]      # 加载类名样本（排序、受 limit 限制）
diagnostics capabilities        # 重定义/重转换能力与 Agent 参数
diagnostics memory sample       # getObjectSize 浅层大小
```

`diagnostics memory` 只调用 `Instrumentation.getObjectSize`，结果**不包含引用对象图**，
不是完整内存占用。

### JMX

注册 `fc.agent:type=Diagnostics`（Standard MBean），属性包含已加载/累计加载/卸载类数、
转换次数、重定义/重转换支持、采样时间、Agent 参数与类名样本；操作为
`estimateShallowSize(String target)`，支持 `diagnostics`、`transformer`、`sample`。

### API

`java.lang.instrument.Instrumentation`、`ClassFileTransformer`、
`java.lang.management.ManagementFactory`/`ClassLoadingMXBean`、`javax.management.*`。

### 限制

- Agent 未加载时 `diagnostics` 输出明确的 `-javaagent` 启动提示。
- 本版不执行 `redefineClasses`/`retransformClasses`；只报告 JVM 能力。
- 加载类统计与 `ClassLoadingMXBean` 的累计值口径可能不同（数组/隐藏类）。

## 2. 联机安全认证中心（`java.security.sasl` / `java.security.jgss`）

### SASL

```text
auth mechanisms
auth sasl <用户> <口令> [授权身份]
auth kerberos
auth status
```

- 所有认证都通过标准 `Sasl.createSaslClient` / `Sasl.createSaslServer` 建立，
  机制由 `fc-extensions` 无关的 `fc-auth` 模块提供。
- 内置 `FC-CHALLENGE`：服务端发送随机 nonce；客户端返回用户名、授权身份与
  `HMAC-SHA256(nonce|username|authz, password)`，不传明文口令；服务端通过
  `CallbackHandler`（`NameCallback`/`PasswordCallback`/`RealmCallback`/
  `AuthorizeCallback`）查目录与授权。成功返回 `AuthIdentity`。
- 外部机制（如 PLAIN、GSSAPI）是否存在由运行环境的 Provider 决定；
  `auth mechanisms` 列出实际可用集合。机制不支持、用户不存在、口令错误、
  授权身份不匹配都会返回明确失败，不伪造成功。
- `LocalCallbacks` 对不支持的 Callback 抛 `UnsupportedCallbackException`；
  `SaslClient`/`SaslServer` 在 `finally` 中 `dispose()`，测试覆盖释放后的调用。
- 演示账号：`captain`/`frontier`、`crew`/`patrol`，可通过
  `-Dfc.auth.demo.user`/`-Dfc.auth.demo.password` 覆盖 captain。
- 认证身份只是上下文；不会自动获得 Mod 加载或服务器管理权限。单机模式不依赖 SASL。

### Kerberos / GSSAPI

`auth kerberos` 使用 `GSSManager`、`GSSContext`、`GSSName`、`GSSCredential`、`Oid`、
`MessageProp` 等标准 GSS API 进行探测与上下文建立入口；服务端接受凭据按
`GSSCredential.ACCEPT_ONLY` 获取。

启用真实登录所需环境（示例）：

```text
-Djava.security.krb5.realm=EXAMPLE.COM
-Djava.security.krb5.kdc=kdc.example.com
-Dfc.auth.gss.service=frontier/localhost@EXAMPLE.COM
-Dfc.auth.gss.keytab=/path/frontier.keytab
```

`KerberosEnvironment`/`KerberosPrincipal`/`KeyTab` 检查主体与 keytab；没有 realm/KDC
或 keytab 时 `auth kerberos` 明确报告“未就绪”。GSS 认证步骤的
`GSSException` 保留 major/minor 码并转成可读原因（票据过期、凭据缺失、
Token 损坏、KDC 不可达等）。认证完成后 `Handshake.close()` 释放 `GSSContext`。
**没有 KDC 时不存在成功的测试路径**；仓库测试只验证探测与降级，不伪造登录。

## 3. 离线编年史数据工作台（`java.sql.rowset`）

```text
workbench show [页] [每页]
workbench filter from=2 to=5 category=Fleet location=L1 actor=7 text=金属 type=IonStorm
workbench sort day desc [k=v...]
workbench join [k=v...]
workbench export <文件.xml> [k=v...]
workbench import <文件.xml>
```

- `ChronicleWorkbench.load` 复用现有只读 JDBC 驱动（`jdbc:fc:chronicle:<会话>`），
  通过 `CachedRowSet.populate(ResultSet)` 把结果读入内存；连接立即关闭，
  后续过滤、排序、分页、连接与 XML 操作离线完成。
- `FilteredRowSet` + `Predicate` 支持日区间、类别、类型、地点、参与主体与全文；
  注意 JDK `FilteredRowSet.size()` 不反映过滤结果，工作台改用实际遍历计数。
- `sort` 复制为新 `CachedRowSet`，不改变原结果；JDK `CachedRowSetImpl` 的新行遍历
  顺序与插入顺序相反，内部按逆序写入以保持目标顺序。
- `JoinRowSet` 把事件与地点目录（`L<id>`、名称、类型、归属）连接。
- `WebRowSet` 负责标准 XML 导入/导出；每行带 `schema_version=fc-workbench/1`，
  导出时也显式写入元数据，导入时检查必需列与版本。非法 XML、缺少元数据、
  缺列、版本不兼容、空数据都会以 `SQLException` 拒绝。
- `RowSetMetaDataImpl`/`RowSetProvider`/`RowSetFactory` 用于构建目录与复制结果集。
- 命令 `workbench` 不进入重放日志；生成的 XML 是标准 UTF-8 WebRowSet 文档，
  可用于外部分析或后续导入。

## 4. 测试与验证

- `fc-agent`：`AgentProbe` 4 例，在真实 `-javaagent` JVM 中验证 MBean、能力、
  类名排序/上限、浅层大小与未知目标拒绝。
- `fc-auth`：`AuthTest` 6 例，覆盖机制发现、多轮成功交换、错误口令/未知用户/
  不支持机制/授权不匹配、CallbackHandler、资源释放与 Kerberos 降级。
- `fc-history`：`ChronicleWorkbenchTest` 5 例，覆盖离线结果集、过滤、排序分页、
  WebRowSet XML 版本校验、Join。
- `fc-runtime`：`RuntimeSmokeTest` 增加端到端命令用例；`SplitConsoleTest` 保持不变。
- 全量构建：**33 个测试套件 / 273 个用例通过**（含 Agent 测试 JVM），
  Audit 为类型 628/3836（16.4%）、包 88/176（50.0%）、模块 **20/20**、
  417 个 class、Preview 违规 0。

## 5. API 与功能对照

| 功能 | 主要 Java SE API |
|---|---|
| 诊断 Agent | `java.lang.instrument.Instrumentation`、`ClassFileTransformer`；JMX；`ClassLoadingMXBean` |
| SASL | `javax.security.sasl.Sasl`、`SaslClient`、`SaslServer`、`SaslClientFactory`、`SaslServerFactory`、`SaslException`、`AuthorizeCallback`、`RealmCallback`；`CallbackHandler` 家族 |
| Kerberos | `GSSManager`、`GSSContext`、`GSSName`、`GSSCredential`、`Oid`、`GSSException`、`MessageProp`；`KerberosPrincipal`、`KeyTab` |
| 离线工作台 | `RowSetProvider`、`RowSetFactory`、`CachedRowSet`、`FilteredRowSet`、`RowSetMetaDataImpl`、`Predicate`、`JoinRowSet`、`WebRowSet` |

## 6. 外部环境说明

- Agent 无需额外环境；只需用 `-javaagent` 启动。
- SASL `FC-CHALLENGE` 自包含，不依赖外部 Provider；其他机制是否可用由环境决定。
- Kerberos 完整登录需要 KDC、服务主体与 keytab/票据；没有环境时只做探测与失败处理。
- RowSet 使用 JDK 自带提供者（`RowSetProvider.newFactory()`），无需第三方依赖。
