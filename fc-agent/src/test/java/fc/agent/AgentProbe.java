package fc.agent;

import fc.testkit.FcTest;
import fc.testkit.TestRunner;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

/** 在 -javaagent 启动的 JVM 中验证诊断 MBean 的注册、采样与浅层大小操作。 */
public final class AgentProbe {

    public static void main(String[] args) {
        boolean passed = TestRunner.report(TestRunner.run(AgentProbe.class));
        System.exit(passed ? 0 : 1);
    }

    private static MBeanServer server() {
        return ManagementFactory.getPlatformMBeanServer();
    }

    private static ObjectName name() throws Exception {
        return new ObjectName(Diagnostics.OBJECT_NAME);
    }

    @FcTest
    public void agentRegistersDiagnosticsMBean() throws Exception {
        assert server().isRegistered(name()) : "Agent 未注册诊断 MBean";
        assert ((Integer) server().getAttribute(name(), "LoadedClassCount")) > 0;
        assert ((Long) server().getAttribute(name(), "TotalLoadedClassCount")) > 0;
        assert ((Long) server().getAttribute(name(), "TransformationCount")) > 0
                : "应观察到类加载/转换";
    }

    @FcTest
    public void reportsCapabilitiesAndArguments() throws Exception {
        assert Boolean.TRUE.equals(server().getAttribute(name(), "RedefinitionSupported"));
        assert Boolean.TRUE.equals(server().getAttribute(name(), "RetransformationSupported"));
        String arguments = (String) server().getAttribute(name(), "AgentArguments");
        assert arguments.contains("limit=2000") : arguments;
        assert ((Integer) server().getAttribute(name(), "ClassListLimit")) == 2000;
        assert !((String) server().getAttribute(name(), "SampleTime")).isBlank();
    }

    @FcTest
    public void listsLoadedClassesSortedAndLimited() throws Exception {
        String[] names = (String[]) server().getAttribute(name(), "ClassNames");
        assert names.length > 0 && names.length <= 2000 : names.length;
        for (int i = 1; i < names.length; i++) {
            assert names[i - 1].compareTo(names[i]) <= 0 : "类名必须排序";
        }
        assert java.util.Arrays.asList(names).contains(AgentProbe.class.getName())
                : "应包含探针自身";
    }

    @FcTest
    public void estimatesShallowSizeAndRejectsUnknownTargets() throws Exception {
        long size = (Long) server().invoke(name(), "estimateShallowSize",
                new Object[] {"diagnostics"}, new String[] {"java.lang.String"});
        assert size > 0 : size;
        boolean rejected = false;
        try {
            server().invoke(name(), "estimateShallowSize",
                    new Object[] {"no-such-object"}, new String[] {"java.lang.String"});
        } catch (javax.management.RuntimeMBeanException
                 | javax.management.MBeanException e) {
            rejected = true;
            Throwable cause = e.getCause() == null ? e : e.getCause();
            assert cause.getMessage().contains("未知诊断对象") : cause.getMessage();
        }
        assert rejected : "未知对象必须拒绝";
    }
}
