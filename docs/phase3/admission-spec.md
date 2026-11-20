# 阶段三（v3）：API 准入体系与数据模型规范

> 本文件是阶段三重构后所有数据、规则、生成器的**唯一权威定义**。
> 前置：项目目标已变更为 **B（API 覆盖优先，游戏作为承载载体）**，见 `docs/phase3/project-direction.md`。
> 阶段调整：阶段三=API 机会盘点 → 阶段四=类型级准入与论证 → 阶段五=分批实现 → 阶段六=最终覆盖审计。本阶段**不编码、不启动 M0**。

---

## 1. 功能分类 feature_origin

四类功能拥有**同等**的 API 准入资格，不再要求所有 API 都服务传统游戏玩法：

| 值 | 含义 | 典型方向 |
|---|---|---|
| `CORE` | 原始游戏核心功能 | 世界状态、舰队航行、经济结算、编年史 |
| `API_DRIVEN` | 由标准 API 能力驱动的新功能 | 远程观战、运行监控、规则编译、XML 签名 |
| `INFRASTRUCTURE` | 应用运行基础设施 | 存档加密、配置装载、日志、偏好 |
| `DEVELOPER_TOOLING` | 开发、扩展、测试和诊断工具 | 注解处理器、覆盖审计、模组装载、jlink 装配 |

一个类型可以归属多个功能，但**每个（类型, 功能）映射关系必须分别论证**（生成器硬校验）。

---

## 2. 类型状态 status

| 状态 | 含义 | 是否进入覆盖分子 |
|---|---|---|
| `Planned` | 已通过 L3 类型级论证，进入实现计划 | 是（规划覆盖） |
| `Implemented` | 已实现有效使用（阶段五回填） | 是 |
| `Verified` | 已通过 L4 集成验证（阶段六回填） | 是 |
| `Pending` | 功能未设计或现行约束下不可用 | 否，也不计入排除 |
| `Excluded` | 经论证不使用 | 否（计入排除） |

---

## 3. 论证层级 justification_level

| 级别 | 名称 | 是否允许 Planned | 说明 |
|---|---|---|---|
| `L0` | MODULE | **否** | 只有模块级理由（"属于这个模块"），不能支撑任何 Planned |
| `L1` | PACKAGE | **否** | 只有包级理由（"这个包要做"），不能支撑 Planned |
| `L2` | FEATURE | **否**（仅候选） | 有明确功能与验收标准，但尚未落到具体类型 |
| `L3` | TYPE | **是** | 明确说明该类型对该功能的贡献（api_role） |
| `L4` | INTEGRATION | **是（可 Verified）** | 完整实现 + 测试 + 运行证据 |

**核心规则**：`Planned` 必须达到 L3；L0/L1 只能产出 `Pending` 或 `Excluded`，不能产出 `Planned`。这条由生成器硬断言。

---

## 4. 锚点类型 anchor_type

| 值 | 含义 | 适用功能 |
|---|---|---|
| `PLAYER` | 玩家可见验收（`> 命令` 产生可观察效果） | CORE、部分 API_DRIVEN |
| `OPERATOR` | 运行管理者可验证（`> diag`、日志、MBean、端口） | INFRASTRUCTURE、API_DRIVEN |
| `DEVELOPER` | 开发者可验证（工具输出、审计报告、编译产物） | DEVELOPER_TOOLING |

**不要求所有 API 都具有玩家可见性**：基础设施用 OPERATOR，开发工具用 DEVELOPER。锚点类型由功能锚点声明，映射行继承。

---

## 5. api_role（类型在功能中的角色）

每个类型必须有明确的 `api_role`，说明它对该功能的具体贡献。取值建议：

| 值 | 含义 |
|---|---|
| `FACTORY` | 工厂/入口，创建核心对象 |
| `MODEL` | 数据模型/值对象，承载状态 |
| `SERVICE` | 服务对象，执行操作 |
| `PROTOCOL` | 协议/约定接口，定义交互契约 |
| `TRANSPORT` | 传输/编解码，承载数据流 |
| `CONTROL` | 控制/生命周期管理 |
| `QUERY` | 查询/检索/遍历 |
| `ERROR` | 异常/错误处理 |
| `LISTENER` | 回调/事件监听 |
| `METADATA` | 元信息/描述符/注解 |
| `SECURITY` | 安全/权限/信任 |
| `CONFIG` | 配置/参数载体 |

---

## 6. runtime_requirement 与 fallback_strategy

- `runtime_requirement`：运行环境要求。取值：`NONE`（无特殊要求）/ `HEADLESS_OK`（无头可用）/ `DEVICE_OPTIONAL`（设备可选）/ `SERVICE_OPTIONAL`（服务可选）/ `PROVIDER_REQUIRED`（需第三方 Provider）。
- `fallback_strategy`：降级策略。取值：`NONE`（不适用）/ `SILENT_DISABLE`（静默禁用）/ `STUB`（桩实现）/ `DEGRADE`（降级路径，如打印→PNG、音频→静默）/ `REQUIRED`（必需，无降级）。

**核心游戏不得依赖可选外部服务才能启动**；`PROVIDER_REQUIRED` 的功能必须提供 `fallback_strategy` 且不得阻塞启动。

---

## 7. api-mapping.csv 字段定义（v3，26 列）

| 列 | 字段 | 说明 |
|---|---|---|
| 1 | `module` | JDK 模块（来自冻结清单） |
| 2 | `package` | 包名 |
| 3 | `type_binary` | 类型二进制名 |
| 4 | `type_name` | 类型短名（`type_display` 的末段） |
| 5 | `kind` | 类型种类 |
| 6 | `nesting` | top / nested |
| 7 | `deprecated` | yes / no |
| 8 | `for_removal` | yes / no |
| 9 | `preview` | yes / no |
| 10 | `coverage_scope` | IN_DENOMINATOR / EXCLUDED_PREVIEW |
| 11 | `status` | Planned / Implemented / Verified / Pending / Excluded |
| 12 | `feature_name` | 功能锚点名称 |
| 13 | `feature_origin` | CORE / API_DRIVEN / INFRASTRUCTURE / DEVELOPER_TOOLING |
| 14 | `feature_anchor` | 功能锚点 id |
| 15 | `anchor_type` | PLAYER / OPERATOR / DEVELOPER |
| 16 | `justification_level` | L0 / L1 / L2 / L3 / L4 |
| 17 | `justification` | 论证文本（该类型对该功能的贡献） |
| 18 | `api_role` | 类型角色（§5） |
| 19 | `runtime_requirement` | 运行环境要求（§6） |
| 20 | `fallback_strategy` | 降级策略（§6） |
| 21 | `implementation_location` | 预期/实际代码位置（`<fc>/src/main/java/<path>`） |
| 22 | `test_evidence` | 测试证据（阶段五/六回填） |
| 23 | `fc_module` | 归属 fc 模块 |
| 24 | `batch` | 首次实现批次 |
| 25 | `uncovered_reason_code` | Excluded 原因码（BIZ/ENV/ACCESS/DEPRECATED/PREVIEW/SPEC） |
| 26 | `uncovered_reason` | 排除原因说明 |

> 列名由生成器以表头输出，本表为逻辑定义；`type_binary` 即冻结清单的 `type_binary`，`type_name` 取 `type_display` 末段。`coverage_scope` 保留原语义，`protected_nested` 并入 `uncovered_reason_code=ACCESS` 的判定，不再单列。

---

## 8. feature-anchors.tsv 字段（v3，7 列）

| 列 | 字段 | 说明 |
|---|---|---|
| 1 | `feature_id` | 功能唯一 id |
| 2 | `feature_name` | 功能名称 |
| 3 | `feature_origin` | CORE / API_DRIVEN / INFRASTRUCTURE / DEVELOPER_TOOLING |
| 4 | `anchor_type` | PLAYER / OPERATOR / DEVELOPER |
| 5 | `acceptance_criteria` | 验收标准（可验证的一句话） |
| 6 | `verification_method` | 验证方式（命令 / 日志 / 扫描 / 编译 / 运行证据） |
| 7 | `priority` | 优先级（P0 核心 / P1 高 / P2 中 / P3 低 / 待定） |

> 与旧版相比：废弃 `scope` 列（module:/package:/direct: 的隐式绑定），改为**显式 feature_id 引用**——type-keep 与 type-overrides 必须显式写 feature_id，不再有"包级/模块级默认锚点"。

---

## 9. type-keep.tsv 字段（v3，表达功能↔类型准入关系）

| 列 | 字段 | 说明 |
|---|---|---|
| 1 | `package` | 包名 |
| 2 | `feature_id` | 功能 id（显式引用 feature-anchors.tsv） |
| 3 | `types` | 该功能在该包内准入的类型清单（逗号分隔，`*`=整包，`-`=无） |
| 4 | `api_role` | 这些类型在该功能中的统一角色 |
| 5 | `runtime_requirement` | 运行环境要求 |
| 6 | `fallback_strategy` | 降级策略 |
| 7 | `justification` | 论证：这些类型如何共同构成该功能 |
| 8 | `justification_level` | L2 / L3（L3 表示逐类型论证，L2 表示功能级论证、需逐类型补充后转 L3） |
| 9 | `rest_reason_code` | 包内**其余**类型的排除原因（BIZ/ENV/ACCESS/DEPRECATED/SPEC） |
| 10 | `rest_reason` | 其余类型排除的说明 |

> **关键约束**：`type-keep.tsv` 不再产生任何"包级默认 Planned"。每个准入的类型必须通过某个 feature 的 `types` 清单显式进入，且该 feature 的 `justification_level` 达到 L3 时该类型才能 `Planned`；否则降为 `Pending`（L2）或 `Excluded`（未列入任何 feature 且无 rest 覆盖）。

---

## 10. 生成器硬校验清单

1. `Planned` 必须具有有效功能锚点（feature_id 存在）。
2. `Planned` 必须达到 L3 类型级论证（justification_level ∈ {L3, L4}）。
3. 每个类型必须有明确 `api_role`（非 Planned/Excluded 也需，Excluded 可为 `-`）。
4. 一个类型可对应多个功能，每个映射关系分别论证（无重复映射、无冲突状态）。
5. **不允许模块级、包级规则批量继承生成 Planned**（L0/L1 → 非 Planned）。
6. `Pending` 必须记录未决问题和重新评估条件（justification 字段承载）。
7. `Excluded` 必须记录具体原因，并**确认是否进行过 API 驱动功能探索**（`uncovered_reason` 前缀 `[未探索]` / `[已探索]`）。
8. `ENV` 必须具有可复现的运行时证据（指向 `data/verification/`）。
9. 功能锚点必须具备实际验收方式（`verification_method` 非空）。
10. 功能锚点与类型映射**双向完整性**：每个 feature_id 至少映射一个类型；每个 Planned 类型的 feature_id 必须存在。
11. 检查孤立锚点（feature 无类型映射）、无效锚点（feature_id 不存在）、重复映射、非法状态转换。
12. 保留正式 API 分母（3836）和原始规范清单，不得通过功能设计缩减统计全集。

---

## 11. 阶段四的批次准入（前置约定）

- 每批开工前，该批涉及的包必须完成 L3 类型级论证（逐类型 api_role + justification）。
- 每批验收回答"玩家/运营者/开发者多了什么能力"，而非"用了多少类型"。
- 覆盖统计只计算 Java SE 21 标准 API 类型；第三方 Provider 不得计入。
