package fc.extensions;

import fc.testkit.FcTest;
import fc.testkit.TestRunner;

import java.util.ArrayList;
import java.util.List;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import javax.script.SimpleScriptContext;

/** Frontier Script：ServiceLoader 发现、变量、repeat、编译与错误语义。 */
public final class FcScriptTest {

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(FcScriptTest.class))) {
            System.exit(1);
        }
    }

    @FcTest
    public void engineIsDiscoveredThroughServiceLoader() {
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("frontier");
        assert engine != null : "ScriptEngineManager 未发现 frontier 引擎";
        assert engine instanceof Compilable : "引擎应支持预编译";

        ScriptEngineFactory factory = engine.getFactory();
        assert factory.getNames().contains("frontier") : factory.getNames();
        assert factory.getNames().contains("fcs") : factory.getNames();
        assert factory.getExtensions().contains("fcs") : factory.getExtensions();
        assert factory.getMimeTypes().contains("text/x-frontier-script")
                : factory.getMimeTypes();
        assert factory.getLanguageName().equals("Frontier Script");
        assert "frontier".equals(factory.getParameter(ScriptEngine.NAME));
        assert factory.getParameter("no-such-key") == null;
        assert manager.getEngineByName("fcs") != null : "别名也应可用";
    }

    @FcTest
    public void evalRunsCommandsAndSubstitutesVariables() throws Exception {
        ScriptEngine engine = newEngine();
        List<String> ran = new ArrayList<>();
        engine.put(FcScriptEngine.HOST,
                (FcScriptEngine.Host) command -> {
                    ran.add(command);
                    return "ok:" + command;
                });

        Object result = engine.eval("""
                # 一次常规巡逻
                set target 边境
                echo 前往 ${target}
                next 2
                market ${target}
                """);

        assert ran.equals(List.of("next 2", "market 边境")) : ran;
        assert result.toString().contains("前往 边境") : result;
        assert result.toString().contains("ok:market 边境") : result;
        assert engine.get(FcScriptEngine.LAST).equals("ok:market 边境")
                : engine.get(FcScriptEngine.LAST);
    }

    @FcTest
    public void repeatExpandsItsBodyAndRejectsBadCounts() throws Exception {
        ScriptEngine engine = newEngine();
        List<String> ran = new ArrayList<>();
        engine.put(FcScriptEngine.HOST, (FcScriptEngine.Host) command -> {
            ran.add(command);
            return "";
        });

        engine.eval("repeat 3 next 1");
        assert ran.equals(List.of("next 1", "next 1", "next 1")) : ran;

        ran.clear();
        engine.eval("set n 1\nrepeat 2 echo 第 ${n} 次");
        assert ran.isEmpty() : "echo 不应进入宿主";
        assert engine.eval("repeat 0 next 2").toString().isEmpty();

        assertThrows(ScriptException.class, () -> engine.eval("repeat -1 next 1"));
        assertThrows(ScriptException.class, () -> engine.eval("repeat 1001 next 1"));
        assertThrows(ScriptException.class, () -> engine.eval("repeat x next 1"));
        assertThrows(ScriptException.class, () -> engine.eval("repeat 2"));
    }

    @FcTest
    public void compiledScriptsAreReusable() throws Exception {
        ScriptEngine engine = newEngine();
        List<String> ran = new ArrayList<>();
        engine.put(FcScriptEngine.HOST, (FcScriptEngine.Host) command -> {
            ran.add(command);
            return command;
        });

        Compilable compilable = (Compilable) engine;
        CompiledScript compiled = compilable.compile("set port 4100\necho ${port}\nsay hello");
        assert compiled.eval().toString().contains("4100");
        assert ran.equals(List.of("say hello")) : ran;
        assert compiled.eval().toString().contains("4100") : "编译脚本应可重复执行";
        assert ran.size() == 2 : ran;
        assert compiled.getEngine() == engine;

        assertThrows(ScriptException.class, () -> compilable.compile("repeat 2"));
        assertThrows(ScriptException.class, () -> compilable.compile("set"));
    }

    @FcTest
    public void syntaxHostAndVariableErrorsSurfaceAsScriptException() throws Exception {
        ScriptEngine engine = newEngine();
        assertThrows(ScriptException.class, () -> engine.eval("say hi"));
        assertThrows(ScriptException.class, () -> engine.eval("echo ${missing}"));
        assertThrows(ScriptException.class, () -> engine.eval("set only-name"));

        engine.put(FcScriptEngine.HOST, (FcScriptEngine.Host) command -> {
            throw new IllegalStateException("世界已暂停");
        });
        ScriptException failure = capture(() -> engine.eval("say hi"));
        assert failure.getMessage().contains("世界已暂停") : failure.getMessage();
        assert failure.getLineNumber() == 1 : failure.getLineNumber();
    }

    @FcTest
    public void bindingsAndFactorySyntaxHelpersWork() throws Exception {
        ScriptEngine engine = newEngine();
        ScriptEngineFactory factory = engine.getFactory();
        assert factory.getOutputStatement("你好").equals("echo 你好");
        assert factory.getProgram("echo 甲", "echo 乙").equals("echo 甲\necho 乙");
        assert factory.getMethodCallSyntax("ship", "sail", "边境")
                .equals("ship.sail(边境)");

        SimpleScriptContext context = new SimpleScriptContext();
        Bindings bindings = new SimpleBindings();
        bindings.put("target", "前哨");
        context.setBindings(bindings, ScriptContext.ENGINE_SCOPE);
        context.setAttribute(FcScriptEngine.HOST,
                (FcScriptEngine.Host) command -> "ran:" + command,
                ScriptContext.ENGINE_SCOPE);

        Object out = engine.eval("echo 前往 ${target}\nscan", context);
        assert out.toString().contains("前往 前哨") : out;
        assert out.toString().contains("ran:scan") : out;
        assert bindings.get(FcScriptEngine.LAST).equals("ran:scan");

        Bindings created = engine.createBindings();
        assert created.isEmpty();
        assert engine.eval("", context).toString().isEmpty() : "空脚本无输出";
    }

    private static ScriptEngine newEngine() {
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("frontier");
        assert engine != null : "未注册 frontier 引擎";
        return engine;
    }

    private static ScriptException capture(ThrowingCall call) throws Exception {
        try {
            call.run();
        } catch (ScriptException e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError("期望 ScriptException，实际 "
                    + e.getClass().getSimpleName() + "：" + e.getMessage(), e);
        }
        throw new AssertionError("期望抛出 ScriptException，但没有异常");
    }

    private static void assertThrows(Class<? extends Throwable> type, ThrowingCall call)
            throws Exception {
        try {
            call.run();
        } catch (Throwable e) {
            if (type.isInstance(e)) {
                return;
            }
            throw new AssertionError("期望 " + type.getSimpleName() + "，实际 "
                    + e.getClass().getSimpleName() + "：" + e.getMessage(), e);
        }
        throw new AssertionError("期望抛出 " + type.getSimpleName() + "，但没有异常");
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
