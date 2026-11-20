# Java SE 21 基准清单 · 正式冻结说明（阶段一）

> 冻结日期：2026-11-20 ｜ 生成工具：`tools/FreezeBaseline.java`（JDK 21，零依赖）
> 冻结依据：用户裁决「正式采用 3874 公开类型 · 177 包 · 20 内容模块」
> 本文件与下列清单由工具生成，**不修改** `api-baseline.csv` 及任何既有审计证据。

## 1. 用户裁决的正式统计边界（冻结，不得再变更）

| 项 | 裁决 | 落实 |
|---|---|---|
| 正式清单边界 | **3874 公开类型 · 177 包 · 20 内容模块** | `coverage-baseline.csv` 的 3874 行（spec_status=SE） |
| java.smartcardio | 排除正式分母，保留扩展清单 | `extension-smartcardio.csv`（13） |
| protected 嵌套类型 | 排除正式分母，保留扩展清单 | `extension-protected-nested.csv`（168） |
| Deprecated 类型 | 继续纳入 | 68（含 forRemoval 36）全部位于 `coverage-baseline.csv` |
| Preview 类型 | 继续排除正式分母，单独登记 | 38 行标 `EXCLUDED_PREVIEW`，另存 `extension-preview.csv` |

## 2. 两层口径（规范原因，非统计矛盾）

- **清单登记边界（用户冻结）**：3874 类型 / 177 包 —— 即 JSR 396 §7 严格口径的公开类型全集，
  其中 38 个类型级 Preview 类型仍在清单内（以 `coverage_scope=EXCLUDED_PREVIEW` 单列）。
- **覆盖率计算分母（任务书 §3.3 派生）**：**3836 类型 / 176 包**。
  `3874 − 38 = 3836`；包级另扣除 `java.lang.foreign`（25 个类型全部为 Preview，整包无正式 API），
  故 `177 − 1 = 176`。8 个「仅含成员级预览方法」的类型（preview_members=yes）类型本身非预览，
  **保留在分母**。Deprecated 68 个按裁决保留在分母。
- 三个覆盖率指标（任务书 §4）的分母统一取 **3836 类型 / 176 包 / 20 模块**；
  若后续裁决改为「Preview 保留在分母」，只需将 `EXCLUDED_PREVIEW` 行并入分母，数字回到 3874 / 177。

## 3. 文件清单与完整性（SHA-256）

> **校验前提**：上表 SHA-256 均基于 **LF 行尾内容**计算（`FreezeBaseline.java` 以 `\n` 写出）。
> Windows 默认 `core.autocrlf=true` 会把工作区检出为 CRLF，导致直接对工作区文件取哈希不匹配——这不是数据漂移。
> 仓库根目录的 `.gitattributes` 已固定 `*.csv` / `*.md` / `*.java` / `*.sh` 为 `eol=lf`，重新检出后哈希可直接复现；
> 若旧工作区仍为 CRLF，可执行 `git add --renormalize .` 后重新检出，或将内容按 CRLF→LF 归一化后再校验。

| 文件 | 行数（含表头） | 说明 | SHA-256 |
|---|---|---|---|
| `coverage-baseline.csv` | 3875 | SE 正式清单 3874 行（分母 3836 + Preview 38） | `884b9a60a938261bfc8837a6cee5bbed6d8511c37a609ca98370792386ffb086` |
| `extension-smartcardio.csv` | 14 | 13 行，排除分母 | `46b4bbc4e4a7e89f2fada119ec0d5e1234a16a51299af02d48ac7b64c1b75d23` |
| `extension-preview.csv` | 39 | 38 行，排除分母 | `9c81e9a5e9abd3493ba9638553e98f6d46e4415c30ceabd31d716c1c18d56e0b` |
| `extension-protected-nested.csv` | 169 | 168 行，排除分母 | `661b158e5fd8c00a0c03e18bfa52ea46a44f7255789ae50a27dd29d6785e4625` |

## 4. 阶段三使用约定

阶段三在 `coverage-baseline.csv` 上**追加** `mapping_status`（Planned/Implemented/Verified/Excluded/Pending）及场景、代码位置、证据、未覆盖原因等列；
`EXCLUDED_PREVIEW` 行的 mapping_status 固定为 `Excluded`（原因：任务书禁止 Preview API 作为正式实现）。
三份扩展清单**不进入**覆盖率分母，仅作完整性登记与审计追溯。

## 5. 复现

```bash
D:/Java/ms-21.0.13.0.2/bin/java tools/FreezeBaseline.java docs/phase1/data
```
工具对冻结常量（类型/包/扩展/Deprecated 计数）做硬断言，任何数量变化都会直接报错而非静默改写；
上表 SHA-256 用于留痕与人工复核，工具本身不校验历史哈希。若需验证当前工作区是否与冻结内容一致，请按 §3 的 LF 前提比对。
