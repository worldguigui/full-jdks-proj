# full-jdks-proj 项目长期记忆

## 项目
Frontier Chronicle（边境纪事）：Java SE 21 全量标准 API 星际远征 + 舰队经营 + 自主世界模拟游戏（见 README）。任务书要求分阶段：阶段一盘点 → 阶段二设计 → 阶段三映射 → 阶段四分批实现 → 阶段五审计；阶段间须用户确认。

## 环境
- 固定 JDK：`D:/Java/ms-21.0.13.0.2`（系统默认 java=1.8，勿直接调 `java`）。
- Shell 为 Windows Git Bash；中文输出偶发 GBK 乱码，文件一律 UTF-8。

## 阶段一核心数据（已冻结，复用勿重扫）
- **冻结清单边界**：20 SE 内容模块 / 177 包 / 3874 类型（用户 2026-11-20 裁决）。宽口径含 java.smartcardio：178/3887（JAVA_PREFIX_ONLY）。
- **覆盖率计算分母**：3836 类型 / 176 包 / 20 模块 = 3874−38(Preview) / 177−1(java.lang.foreign 整包纯 Preview)。任务书 §3.3 派生，见 api-inventory.md §0.2。
- 冻结裁决：smartcardio（13）、protected 嵌套（168）、Preview（38）排除分母但保留扩展清单；Deprecated 68（forRemoval 36）继续纳入。
- 预览 38、@Deprecated 68、SE 21 无 record 类型（仅版本统计结论；record 语言特性 Java 16 JEP 395 定稿）。
- 数据：`api-baseline.csv`（11 列，只读基准）；`coverage-baseline.csv`（14 列，**阶段三在其上扩 mapping_status**，3874 行 = 3836 IN_DENOMINATOR + 38 EXCLUDED_PREVIEW）；`extension-*.csv`（13/38/168）；`freeze-manifest.md`（SHA-256）。
- 工具：`tools/FreezeBaseline.java`（冻结，硬断言）。全量差异审计证据 `data/verification/`（缺失 168 / 额外 0 / 无法判断 0）。
- java.smartcardio：官方 API 文档按 java.* 前缀分组收录，但**不属于 JSR 396 §7 标准模块集合**；不得称 JDK 专有 API。

## 阶段二（2026-11-20 重写，待用户确认）
- 方向以 README Prompt 为准：星际远征 + 舰队经营 + 自主世界模拟；**旧的聚落经营版 game-design.md 已作废重写**（651 行，19 章）。
- 核心架构：统一世界规则（Location/Link/Agent/Stock+ResourceLedger/WorkUnit 五抽象 + 单一 Stardate + 单一 Chronicle），星域与殖民地是同一套规则的不同尺度（任务书硬要求）。
- 初始规模：1 星域 / 16 地点 / 4 势力 / 3→8 船 / 36 船员 / 1 殖民地 / 12 资源；14 个 fc- 模块；M0–M6 分批。
- `docs/phase2/phase2-report.md` 为阶段报告；**确认前不进阶段三、不编码、不做逐类型映射**。

## 约定
- 游戏代码模块前缀 `fc-`，Maven 多模块，零第三方 compile/runtime 依赖，测试用自研 fc-testkit。
- 每完成一个阶段向 `docs/phaseN/` 交付 md + 数据；扫描/审计工具放 `tools/`。
