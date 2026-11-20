# 命名体系规范：fc:// 统一资源命名

> 决策依据：用户裁决「命名上下文 = fc:// 作为项目统一资源命名体系，明确解析、注册和生命周期语义」。
> 本文档定义 fc:// 的**解析、注册、生命周期**三方面语义，是 JNDI（`javax.naming`）能力簇的设计规范。

---

## 1. 定位

`fc://` 是本项目**统一的资源命名体系**，取代散落的字符串路径约定。所有需要"按名字定位"的资源——mod、配置、存档槽、编年史分片、服务对象——都通过 fc:// 命名上下文访问。

实现载体：`javax.naming` 的自研 `InitialContextFactory`（`fc://` 协议），提供进程内命名上下文，**不依赖任何外部目录服务器**。

## 2. 命名格式

```
fc://<scope>/<path>[/<subpath>...]
```

| 段 | 说明 | 示例 |
|---|---|---|
| `fc` | 固定协议头 | `fc` |
| `scope` | 顶级命名空间 | `mod` / `config` / `save` / `service` / `chronicle` |
| `path` | 作用域内路径 | `combat` / `sector-04` / `slot-3` |

示例：
- `fc:mod/combat` —— mod 资源（mod 名 `combat`）
- `fc:config/recipes` —— 配方配置
- `fc:save/slot-3/world.dat` —— 存档槽 3 的世界数据
- `fc:service/watch` —— 观战服务对象

## 3. 解析语义

1. **绑定**：每个命名条目是一个 `Binding`（`Name` → `Object`）。对象通过 `Reference`/`Referenceable` 表达"可重建引用"，通过 `ObjectFactory` 反序列化为实例。
2. **查找**：`Context.lookup("fc:mod/combat")` 返回绑定对象；`Name`/`CompositeName` 表达多级路径。
3. **枚举**：`NamingEnumeration` 枚举某 scope 下所有绑定（`> res list`）。
4. **命名异常**：解析失败抛 `NamingException` 家族（`NameNotFoundException`/`NoInitialContextException`/`NotContextException` 等），转成可读错误。

## 4. 注册语义

- **谁注册**：`fc-config` 启动时注册系统资源；`fc-extensions` 的 mod 装载器注册 mod 资源。
- **如何注册**：自研 `InitialContextFactory`（`javax.naming.spi`）+ `ObjectFactory`（`javax.naming.spi`）把 fc:// 协议绑定到进程内 Context 实现。
- **命名 SPI**：`InitialContextFactory` / `ObjectFactory` / `NamingManager` 是扩展点，供 mod 注册自定义资源类型。

## 5. 生命周期语义

| 阶段 | 语义 |
|---|---|
| **启动** | `InitialContextFactory` 注册 fc:// 协议 → 系统资源（config/save/service）绑定 |
| **运行** | `> res locate` 查找、`> res list` 枚举、`> res register` 注册新资源 |
| **mod 装载** | mod 资源绑定到 `fc:mod/<name>`；卸载时解绑 |
| **资源变更** | `javax.naming.event` 的 `NamespaceChangeListener` 通知缓存失效 |
| **关闭** | 解除全部绑定，释放 Context（不依赖外部服务，进程内自清理） |

## 6. 类型归属

| 类型 | 角色 | 归属 feature |
|---|---|---|
| `Context` / `InitialContext` / `Name` / `CompositeName` / `CompoundName` | 命名上下文与名称表示 | JNDI-LOOKUP（L3） |
| `Binding` / `NameClassPair` / `NamingEnumeration` | 绑定与枚举 | JNDI-LOOKUP（L3） |
| `Reference` / `Referenceable` / `RefAddr` / `StringRefAddr` | 对象引用 | JNDI-LOOKUP（L3） |
| `InitialContextFactory` / `ObjectFactory` / `NamingManager` | 命名 SPI 扩展点 | JNDI-LOOKUP（L3） |
| `NamespaceChangeListener` / `NamingEvent` | 资源变更事件 | JNDI-LOOKUP（L2） |
| `LinkRef` / `ReferralException` / `LinkException` | 命名链接/引用转发 | JNDI-LOOKUP（L2） |
| `DirContext` / `Attributes` / `BasicAttributes` | 目录属性（mod 元数据） | JNDI-LOOKUP（L2） |
| `LdapName` / `Rdn`（`javax.naming.ldap`） | LDAP 名称解析 | **维持排除**（无目录服务器） |

## 7. 与其它机制的边界

- **不是**文件系统：fc:// 是命名上下文，不是 `FileSystemProvider`（`fcfs://` 那个方向维持 L2 候选，属过度设计，暂不启用）。
- **不是**服务发现（`ServiceLoader`）：`ServiceLoader` 用于 SPI 实现发现，fc:// 用于命名定位，两者互补不重叠。
- **不是**目录服务器（LDAP）：LDAP 子包无目录服务器场景，维持排除。

## 8. 验收

- `> res locate fc:mod/combat` 返回 mod 资源；不存在的名字返回可读错误；
- `> res list fc:mod` 枚举全部已装载 mod；
- mod 卸载后 `> res locate fc:mod/combat` 报 `NameNotFoundException`；
- 全程不连接任何外部目录服务器。
