package fc.agent;

import java.lang.instrument.Instrumentation;

/**
 * Frontier Chronicle 运行时诊断 Agent。
 *
 * <p>通过 {@code -javaagent:fc-agent-*.jar[=limit=N]} 在 JVM 启动时加载；
 * 只注册观察型 {@link java.lang.instrument.ClassFileTransformer} 与 JMX MBean，
 * 不修改任何类的字节码。Agent 未加载时游戏照常运行，诊断命令会明确提示。</p>
 */
public final class FcAgent {

    private FcAgent() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        Diagnostics.install(agentArgs, instrumentation);
    }
}
