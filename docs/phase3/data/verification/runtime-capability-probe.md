# 阶段三复核补充证据：运行时能力可用性（自动生成）

java.version = 21.0.11 / java.home = E:\jdks\ms-21.0.11

## 1. 脚本引擎（javax.script）
ScriptEngineManager.getEngineFactories().size() = 0
ServiceLoader<ScriptEngineFactory> = 0
结论：JDK 21 不再内置任何脚本引擎（Nashorn 已于 JDK 15 移除，JEP 372）。在“零第三方依赖”前提下，javax.script 无法获得任何 ScriptEngine 实例。

## 2. JDBC 驱动（java.sql / javax.sql）
DriverManager.getDrivers().count = 0
ServiceLoader<Driver> = 0
结论：JDK 本身不附带 JDBC 驱动（历史上亦无），DriverManager 在无第三方驱动时无法建立连接；Connections/Statements/ResultSets 全部无从取得，java.sql 与 javax.sql 在零依赖约束下不可用。

## 3. 图像写出格式（用于判断 ImageIO 插件 API 是否必要）
ImageIO.getWriterFormatNames() = [JPG, jpg, tiff, bmp, BMP, gif, GIF, WBMP, png, PNG, JPEG, tif, TIF, TIFF, wbmp, jpeg]
ImageIO.write(BufferedImage,"tiff",OutputStream) 是否需要 TIFF 插件 API：否，标准 writer 已注册。
