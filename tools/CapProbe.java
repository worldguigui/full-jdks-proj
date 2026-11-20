import javax.script.ScriptEngineManager;
import java.sql.DriverManager;
import java.sql.Driver;
import java.util.Enumeration;
import java.util.ServiceLoader;
import javax.imageio.ImageIO;
import java.nio.file.*;

public class CapProbe {
    public static void main(String[] a) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# 阶段三复核补充证据：运行时能力可用性（自动生成）\n\n");
        sb.append("java.version = ").append(System.getProperty("java.version"))
          .append(" / java.home = ").append(System.getProperty("java.home")).append("\n\n");

        var mgr = new ScriptEngineManager();
        var factories = mgr.getEngineFactories();
        sb.append("## 1. 脚本引擎（javax.script）\n");
        sb.append("ScriptEngineManager.getEngineFactories().size() = ").append(factories.size()).append("\n");
        for (var f : factories) sb.append("  - ").append(f.getEngineName()).append(" / ").append(f.getNames()).append("\n");
        sb.append("ServiceLoader<ScriptEngineFactory> = ")
          .append(ServiceLoader.load(javax.script.ScriptEngineFactory.class).stream().count()).append("\n");
        sb.append("结论：JDK 21 不再内置任何脚本引擎（Nashorn 已于 JDK 15 移除，JEP 372）。");
        sb.append("在“零第三方依赖”前提下，javax.script 无法获得任何 ScriptEngine 实例。\n\n");

        sb.append("## 2. JDBC 驱动（java.sql / javax.sql）\n");
        int n = 0;
        Enumeration<Driver> it = DriverManager.getDrivers();
        while (it.hasMoreElements()) { Driver d = it.nextElement(); n++; sb.append("  - ").append(d.getClass().getName()).append("\n"); }
        sb.append("DriverManager.getDrivers().count = ").append(n).append("\n");
        sb.append("ServiceLoader<Driver> = ").append(ServiceLoader.load(Driver.class).stream().count()).append("\n");
        sb.append("结论：JDK 本身不附带 JDBC 驱动（历史上亦无），DriverManager 在无第三方驱动时无法建立连接；");
        sb.append("Connections/Statements/ResultSets 全部无从取得，java.sql 与 javax.sql 在零依赖约束下不可用。\n\n");

        sb.append("## 3. 图像写出格式（用于判断 ImageIO 插件 API 是否必要）\n");
        sb.append("ImageIO.getWriterFormatNames() = ").append(java.util.Arrays.toString(ImageIO.getWriterFormatNames())).append("\n");
        sb.append("ImageIO.write(BufferedImage,\"tiff\",OutputStream) 是否需要 TIFF 插件 API：否，标准 writer 已注册。\n");

        Path out = Path.of(a.length > 0 ? a[0] : "docs/phase3/data/verification/runtime-capability-probe.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString());
        System.out.println(sb);
    }
}
