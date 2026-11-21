package fc.agent;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.security.ProtectionDomain;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 诊断采集器：持有 Instrumentation，统计类加载/转换活动并注册 JMX MBean。
 *
 * <p>Transformer 始终返回 {@code null}，表示不改变字节码；它只用于观察。
 * 本实现不执行 redefine/retransform，只报告 JVM 是否支持，避免未经验证的
 * 热替换进入游戏核心。</p>
 */
public final class Diagnostics implements DiagnosticsMBean {

    public static final String OBJECT_NAME = "fc.agent:type=Diagnostics";
    private static final int DEFAULT_LIMIT = 300;

    private static volatile Diagnostics installed;

    private final Instrumentation instrumentation;
    private final String agentArguments;
    private final int classListLimit;
    private final AtomicLong transformations = new AtomicLong();
    private final ClassFileTransformer transformer;
    private volatile String sampleTime = "";

    private Diagnostics(String agentArguments, Instrumentation instrumentation, int limit) {
        this.agentArguments = agentArguments == null ? "" : agentArguments;
        this.instrumentation = instrumentation;
        this.classListLimit = limit;
        this.transformer = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className,
                                    Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain,
                                    byte[] classfileBuffer) {
                if (classfileBuffer != null) {
                    transformations.incrementAndGet();
                }
                // null = 不做任何字节码修改。
                return null;
            }
        };
    }

    static synchronized void install(String agentArguments, Instrumentation instrumentation) {
        if (installed != null) {
            return;
        }
        int limit = parseLimit(agentArguments);
        Diagnostics diagnostics = new Diagnostics(agentArguments, instrumentation, limit);
        instrumentation.addTransformer(diagnostics.transformer);
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(OBJECT_NAME);
            if (!server.isRegistered(name)) {
                server.registerMBean(diagnostics, name);
            }
        } catch (Exception e) {
            System.err.println("诊断 Agent 注册 JMX 失败：" + e.getMessage());
        }
        diagnostics.sampleTime = Instant.now().toString();
        installed = diagnostics;
    }

    static Diagnostics installed() {
        return installed;
    }

    private static int parseLimit(String args) {
        if (args == null) {
            return DEFAULT_LIMIT;
        }
        for (String part : args.split(",")) {
            String[] pair = part.strip().split("=", 2);
            if (pair.length == 2 && pair[0].strip().equalsIgnoreCase("limit")) {
                try {
                    return Math.max(1, Math.min(5_000, Integer.parseInt(pair[1].strip())));
                } catch (NumberFormatException ignored) {
                    return DEFAULT_LIMIT;
                }
            }
        }
        return DEFAULT_LIMIT;
    }

    @Override
    public int getLoadedClassCount() {
        return instrumentation.getAllLoadedClasses().length;
    }

    @Override
    public long getTotalLoadedClassCount() {
        return java.lang.management.ManagementFactory.getClassLoadingMXBean()
                .getTotalLoadedClassCount();
    }

    @Override
    public long getUnloadedClassCount() {
        return java.lang.management.ManagementFactory.getClassLoadingMXBean()
                .getUnloadedClassCount();
    }

    @Override
    public long getTransformationCount() {
        return transformations.get();
    }

    @Override
    public boolean isRedefinitionSupported() {
        return instrumentation.isRedefineClassesSupported();
    }

    @Override
    public boolean isRetransformationSupported() {
        return instrumentation.isRetransformClassesSupported();
    }

    @Override
    public String getSampleTime() {
        return sampleTime;
    }

    @Override
    public String getAgentArguments() {
        return agentArguments;
    }

    @Override
    public int getClassListLimit() {
        return classListLimit;
    }

    @Override
    public String[] getClassNames() {
        Class<?>[] classes = instrumentation.getAllLoadedClasses();
        return Arrays.stream(classes)
                .map(Class::getName)
                .sorted()
                .limit(classListLimit)
                .toArray(String[]::new);
    }

    @Override
    public long estimateShallowSize(String target) {
        Object object = switch (target == null ? "" : target) {
            case "diagnostics" -> this;
            case "transformer" -> transformer;
            case "sample" -> new Sample(getLoadedClassCount(), getTotalLoadedClassCount(),
                    getTransformationCount(), sampleTime);
            default -> throw new IllegalArgumentException("未知诊断对象：" + target
                    + "（可用 diagnostics/transformer/sample）");
        };
        // getObjectSize 是 JVM 相关的浅层大小，不包含引用对象图。
        return instrumentation.getObjectSize(object);
    }

    private record Sample(int loaded, long total, long transformations, String time) {
    }
}
