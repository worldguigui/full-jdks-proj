package fc.agent;

/**
 * 诊断 Agent 的只读 MBean 契约。注册名 {@code fc.agent:type=Diagnostics}。
 */
public interface DiagnosticsMBean {

    int getLoadedClassCount();

    long getTotalLoadedClassCount();

    long getUnloadedClassCount();

    long getTransformationCount();

    boolean isRedefinitionSupported();

    boolean isRetransformationSupported();

    String getSampleTime();

    String getAgentArguments();

    int getClassListLimit();

    /** 当前加载类名（按字母序，最多 classListLimit 个）。 */
    String[] getClassNames();

    /**
     * 指定诊断对象的浅层大小估算（JVM 实现相关），不是完整对象图内存。
     * 支持的目标：diagnostics、transformer、sample。
     */
    long estimateShallowSize(String target);
}
