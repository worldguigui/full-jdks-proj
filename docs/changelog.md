# 变更记录

`README.md` 由项目所有者冻结，是不可改动的事实与规则；自 v0.4.0 起，版本迭代记录在本文件
与本地 Git 提交信息中，格式沿用 README 的「版本号 + 日期 + 里程碑 + 编号条目」。

v0.0.1–v0.3.0 的完整记录仍在 README「版本变更迭代」一节，不在本文件重复。

## v0.5.2 2026-11-21 阶段五 M7：星图工坊与出版级制图

1. **制图管线**：`fc.extensions.MapAtlas` 在无头环境绘制出版级星图：渐变星野、
   `ConvolveOp` 模糊星云、`RescaleOp` 亮度、曲线航线（`QuadCurve2D`/`CubicCurve2D`）、
   多形状节点（星形 `Path2D`、`Ellipse2D`、`RoundRectangle2D`、`Rectangle2D`、`Polygon`、`QuadCurve2D`）、
   罗盘、比例尺、图例面板（`Area` 差分描边）与 `AffineTransformOp` 缩略内嵌图。
2. **排版**：`GlyphVector.getOutline` 渐变标题、`AttributedString`+`TextAttribute.TRACKING`
   副标题、`TextLayout` 绘制、`FontMetrics` 定位与标签底衬。
3. **多格式与元数据**：PNG（tEXt）与 JPEG（COM）写出，`ImageWriter`/`ImageWriteParam`
   控制质量；`readTextMetadata` 读回；`ImageReadParam.setSourceRegion` 读取局部；
   JPEG 前用 `ColorConvertOp` 转 sRGB。
4. **图像工具**：锐化/模糊/亮度/缩略图/区域读取/sRGB 转换均为公开静态方法，有独立测试。
5. **CLI**：新增 `atlas [png|jpeg] [路径]`（别名 `chart`），默认输出 `atlas.png`；
   与 `query`/`export` 一样不进入重放日志。
6. **测试**：新增 `MapAtlasTest` 6 个用例与运行时 1 个集成用例；当前共 15 个套件、
   161 个用例通过；既有测试未删除、未跳过、未放宽。
7. **审计基线**：类型 350/3836（9.1%）、包 57/176（32.4%）、模块 9/20、293 个 class、
   Preview 违规 0。

## v0.5.1 2026-11-21 阶段五 M6：编年史查询、导出与只读 SQL

1. **事件投影**：`fc.history.query.EventView` 把 sealed `GameEvent` 投影为稳定字段
   （日、日期、类型、类别、地点、主体、因果、结果、摘要），作为对象、文件、SQL 三种读取入口的
   唯一数据形态。
2. **查询模型**：`ChronicleQuery` 支持日区间、类别/类型、地点（`3` 与 `L3` 等价）、主体、
   全文检索，按日稳定排序后分页；条件非法时在构造期拒绝。
3. **文件导出**：CSV；确定性 StAX XML（内置 XSD 校验、XPath 计数）；内置 XSLT 生成 HTML
   报表；ZIP 证据包固定包含 XML/CSV/HTML 与 `manifest.properties`。解析器拒绝 DOCTYPE 与
   外部实体，样式表工厂关闭外部访问。
4. **只读 JDBC 驱动**：`jdbc:fc:chronicle[:会话名]`，通过 `META-INF/services/java.sql.Driver`
   自动注册，也可用 `ChronicleDataSource`。基于 `java.lang.reflect.Proxy` 实现
   `Connection`/`Statement`/`PreparedStatement`/`ResultSet`/元数据；SQL 子集支持
   `SELECT` 列清单或 `*`、`COUNT(*) AS n`、`AND` 条件、比较与 `LIKE`、单列排序、`LIMIT/OFFSET`。
   写入与 DDL 一律抛 `SQLFeatureNotSupportedException`；未绑定会话抛 `SQLException`。
5. **CLI**：新增 `query <SQL>` 与 `export <csv|xml|html|zip> [路径]`（默认 `chronicle.<格式>`）。
   两者与存档命令一样不进入重放日志。
6. **测试**：新增 `fc.history.HistoryQueryTest` 10 个用例与 `RuntimeSmokeTest` 1 个集成用例；
   当前共 14 个套件、154 个用例通过；既有测试未删除、未跳过、未放宽。
7. **审计基线**：类型 305/3836（8.0%）、包 53/176（30.1%）、模块 9/20、291 个 class、
   Preview 违规 0。证据见 `docs/audit/`。

## v0.5.0 2026-11-21 阶段五 M5：持久化、扩展与审计

1. **存档/读档**：`fc-persist.SaveRepository` 采用「世界来源 + 有序命令日志 + 状态哈希」重放路线；
   `.fcsave` 为单文件 JAR（`save.properties` + `game.bin`），读档重放后比对 `StateHasher`，不一致即失败。
2. **存档安全**：可选 `PBKDF2WithHmacSHA256`（12 万次）+ `AES/GCM/NoPadding` 加密；
   明文存档始终带 SHA-256 校验；槽位名校验、读取签名验证、临时文件落盘后原子替换。
3. **偏好与本地化**：`PreferenceStore`（`java.util.prefs`，按档案隔离）、`Localization`
   （UTF-8 `ResourceBundle` + `MessageFormat`，关闭默认 Locale 回退，中英文资源包）。
4. **扩展 SPI**：`FrontierMod` / `ModContext` / `ModDescriptor`；`ModLoader` 支持 JAR 与目录、
   API 版本与语义化版本校验、重复 id 拒绝、统一代码签名者要求与签名验证。
5. **运行时编译与宏**：`ModCompiler`（JSR-199 内存/落盘编译、诊断格式化）；
   `Macro`（注释、元数据、`${param}` 展开、保存/读取、顺序执行）。
6. **观察与导出**：`GameTelemetry` 注册 JMX `fc.telemetry:type=Game`；`StatusServer`
   仅绑定回环地址提供 `/status` JSON；`StarMapRenderer` 输出确定性 PNG；`SoundBank` 合成提示音并可优雅降级。
7. **运行时集成**：启动器新增 `save <槽位> [口令]`、`load`、`saves`、`png [路径]`；
   启动参数 `--world`、`--serve[=端口]`；自动装载 `mods/`；每条命令后刷新 JMX。
8. **API 使用审计**：`fc-audit` 读取阶段一冻结基准，解析 class 常量池与描述符/签名，
   生成 Markdown 与 CSV 证据；检测 Preview class 与 `EXCLUDED_PREVIEW` 引用，违规退出码 1。
   审计阶段移至反应堆末尾；证据落在 `docs/audit/`。
9. **当前审计基线**：类型 256/3836（6.7%）、包 48/176（27.3%）、模块 8/20、267 个 class、
   Preview 违规 0。该数字作为后续覆盖扩张的诚实起点，不设硬阈值。
10. **测试**：13 个套件 143 个用例通过（新增 persist 6、extensions 9、runtime persistence 6、audit 5）。

## v0.4.0 2026-11-21 阶段五 M4：科研、外交与探索

1. **统一模型补齐**：`SkillSet` 六技能（驾驶/工程/科研/医疗/贸易/探索）进入生产、航行、科研、
   交易、医疗与探索；`BigDecimal` 工作进度；技能成长按主体与 30 天周期判定。
2. **舰船与船员**：船型、货舱、铺位、船体状态；船员随船移动、同停泊点调动、维修消耗材料与信用点。
3. **设施与岗位**：设施类型、工人容量、实验室与船坞岗位；配方、生产与技能系数联动。
4. **配置化**：技能、船型、设施、事件参数、科技树、初始知识、税率、委托与终局工程均进入
   XML/XSD 与语义校验；默认世界同步迁移。
5. **迷雾与探索**：知识状态、`scan` 近距扫描、`survey` 勘察与取消、发现物、科研数据；
   知识状态进入 `StateHasher`。
6. **科研与效果**：科技树、前置条件、科研项目、`TechEffect` 作用于航线、配方、货舱、工作速度、
   税率与勘察；打通「高效曲率线圈 → 航线变短 → 边远矿点可运营 → 矿物供给增加 → 价格回落」因果链。
7. **外交与冲突**：声望分级、赠礼、贸易/互不侵犯/同盟条约、税率减免、委托生成/接取/交付/到期、
   威胁与冲突结算、船体损伤与势力关系漂移。
8. **目标与终局**：D0–D4 阶段目标、船坞订单与舰队扩容、星门/边境信标工程、双终局与终局后冻结时间。
9. **航行风险**：航段风险、事件修正、船型固有权风险与驾驶技能共同决定航程事故，结果可重放。
10. **新命令**：`scan`、`survey`、`research`、`assign`、`repair`、`order`、`project`、
    `diplomacy`、`gift`、`treaty`、`threaten`、`commission`、`borrow`、`repay`。
11. **测试**：9 个套件 117 个用例通过，其中 `M4GameplayTest` 9 个用例覆盖完整 M4 玩法链；
    既有测试未删除、未跳过、未放宽。
