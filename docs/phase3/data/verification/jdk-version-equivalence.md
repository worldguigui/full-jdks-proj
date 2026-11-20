# JDK 21 落位与基线等价性复核（2026-11-20）

## 0. 背景

阶段一冻结时记录的运行环境为 `D:/Java/ms-21.0.13.0.2`（Microsoft Build of OpenJDK 21.0.13.0.2），该路径在本次会话中已不存在。用户提供的实际路径为：

```
E:/jdks/ms-21.0.11
openjdk version "21.0.11" 2026-04-21 LTS
OpenJDK Runtime Environment Microsoft-13877171 (build 21.0.11+10-LTS)
```

版本号与冻结留痕（21.0.13.0.2）不一致，因此不能仅凭"都是 21 LTS"就假定 API 面相同，须实测。本文件记录三项复核的原始结论与复现命令。

原则：**阶段一已冻结的文件（`api-baseline.csv`、`coverage-baseline.csv`、`freeze-manifest.md`、`docs/phase1/api-inventory.md`）一律不改写**，差异只在新文档中说明，重扫结果另存于本目录。

---

## 1. 基线等价性：重扫 vs 冻结清单

### 1.1 命令

```bash
E:/jdks/ms-21.0.11/bin/java -Dfile.encoding=UTF-8 \
  tools/ApiInventoryScanner.java --out docs/phase3/data/verification/jdk21-rescan
```

（输出写入本目录下的 `jdk21-rescan/`，**不覆盖** `docs/phase1/data/`。）

### 1.2 汇总输出（原样摘录）

```
extra java.*-prefix modules:  [java.smartcardio]
packages: 178
public types total: 3887 (top-level 3362, nested 525)
by kind: {abstract-class=446, annotation=24, class=2256, enum=109, interface=987, sealed-class=30, sealed-interface=35}
deprecated: 68 (forRemoval 36)
preview (class-level @PreviewFeature): 38 (+ member-level-only references: 8)
load failures: 0
```

与阶段一记录完全吻合：3887 类型 / 178 包 / Deprecated 68（forRemoval 36）/ Preview 38 / 加载失败 0。

### 1.3 逐行比对（`jdk21-rescan/api-baseline.csv` vs `docs/phase1/data/api-baseline.csv`）

| 比对项 | 结果 |
|---|---|
| 行数 | 3887 = 3887 |
| 冻结清单有、重扫无（缺失） | **0** |
| 重扫有、冻结清单无（额外） | **0** |
| 共有类型的字段差异（`module`/`package`/`kind`/`nesting`/`deprecated`/`for_removal`/`preview`） | **0** |

结论：**21.0.11 与 21.0.13.0.2 的 Java SE 公开 API 面在此口径下不可区分**。阶段一的 3874 / 177 / 20 与分母 3836 / 176 全部继续有效，阶段三映射表无需重算。

> 说明：本比对只覆盖"类型层面的成员可见性与归属"。若阶段四用到某个方法级签名在补丁版本间发生变化的情形，`javac` 会在编译期直接报错，属可即时发现的问题，不构成静默风险。

---

## 2. 生成器一致性：JDK 17 与 JDK 21 产物对比

`Phase3Mapper` 只读 CSV + 写 CSV，不使用版本相关特性。在两个 JVM 下重跑，产物哈希逐字节一致：

| 文件 | MD5（两版本相同） |
|---|---|
| `api-mapping.csv` | `e5de231a2dae00f178b330883475e5da` |
| `package-plan.csv` | `e3c5e561b6cda26fe657a12012771b14` |
| `module-plan.csv` | `56dbfed7de3f156a89d7ca80d97c2b88` |
| `mapping-summary.md` | `ad546d3df7c8a3737645fb7eb77a7f9a` |

因此此前在 JDK 17 上生成的交付物无需替换。

---

## 3. 环境探测刷新（`env-probe.txt`）

已由 21.0.11 重新生成。与 JDK 17.0.10 版的差异仅三处：

| 项 | JDK 17.0.10 | JDK 21.0.11 |
|---|---|---|
| `java.version` / `vendor` / `java.home` | 17.0.10 / Oracle Corporation / `D:\app\Java` | 21.0.11 / Microsoft / `E:\jdks\ms-21.0.11` |
| `availableLocales` | 1017 | 1069 |
| `ImageIO` 格式名顺序 | 末尾 `jpeg, wbmp` | 末尾 `wbmp, jpeg` |

**能力结论完全不变**：`headless=true`、剪贴板 `HeadlessException`、`Desktop.isDesktopSupported()=false`、打印服务 2 个（均为 PDF 虚拟打印机）、混音器 10 + MIDI sequencer 可用、PNG/TIFF 可写、字体 112 族且 CJK 可显示。

故基于环境证据的 `Excluded/ENV` 判定（211 行）无需调整。

---

## 4. 复现清单

```bash
JDK=E:/jdks/ms-21.0.11/bin/java

# 1) 重扫并与冻结基线比对
$JDK -Dfile.encoding=UTF-8 tools/ApiInventoryScanner.java --out docs/phase3/data/verification/jdk21-rescan

# 2) 重跑映射生成器（哈希应与第 2 节一致）
$JDK -Dfile.encoding=UTF-8 tools/Phase3Mapper.java

# 3) 重新生成环境探测
$JDK -Djava.awt.headless=true -Dfile.encoding=UTF-8 tools/EnvProbe.java docs/phase3/data/env-probe.txt
```
