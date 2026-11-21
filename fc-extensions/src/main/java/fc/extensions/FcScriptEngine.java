package fc.extensions;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.script.AbstractScriptEngine;
import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

/**
 * Frontier Script：把游戏命令编排成可复用的批处理脚本。
 *
 * <p>JDK 21 不附带任何脚本引擎，本引擎是 {@code javax.script} 接口的自研实现，
 * 由 {@link FcScriptEngineFactory} 注册到 {@code ScriptEngineManager}。语法刻意保持
 * 确定性、无副作用之外的隐式行为：</p>
 *
 * <pre>
 * # 注释
 * set target 边境
 * echo 前往 ${target}
 * repeat 3 next 1
 * market ${target}
 * </pre>
 *
 * <p>语句可用换行或 {@code ;} 分隔。普通行交给执行宿主（{@link Host}，由 CLI 注入）
 * 逐条执行；{@code set} 写入引擎
 * 作用域绑定，{@code ${name}} 在运行期替换，未知变量报 {@link ScriptException}。
 * {@code repeat <n> <一行脚本>} 最多展开 1000 次，嵌套深度上限 8。
 * {@link #compile(String)} 只做语法检查并返回可重复求值的 {@link CompiledScript}，
 * 变量替换仍发生在求值期。</p>
 */
public final class FcScriptEngine extends AbstractScriptEngine implements Compilable {

    /** 宿主绑定名：值为 {@link Host}，负责执行一条游戏命令并返回输出。 */
    public static final String HOST = "host";
    /** 最近一次命令输出的绑定名。 */
    public static final String LAST = "last";

    /** 脚本里普通行的执行宿主。 */
    @FunctionalInterface
    public interface Host {
        String execute(String command) throws Exception;
    }

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}");
    private static final int MAX_REPEAT = 1_000;
    private static final int MAX_DEPTH = 8;

    private final ScriptEngineFactory factory;

    public FcScriptEngine(ScriptEngineFactory factory) {
        if (factory == null) {
            throw new IllegalArgumentException("引擎工厂不能为空");
        }
        this.factory = factory;
    }

    @Override
    public Object eval(String script, ScriptContext context) throws ScriptException {
        if (script == null) {
            throw new ScriptException("脚本不能为 null");
        }
        return run(parse(script), context);
    }

    @Override
    public Object eval(Reader reader, ScriptContext context) throws ScriptException {
        if (reader == null) {
            throw new ScriptException("脚本读取器不能为 null");
        }
        try {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[4_096];
            for (int n; (n = reader.read(buffer)) >= 0; ) {
                text.append(buffer, 0, n);
            }
            return eval(text.toString(), context);
        } catch (IOException e) {
            throw new ScriptException("读取脚本失败：" + e.getMessage());
        }
    }

    @Override
    public CompiledScript compile(String script) throws ScriptException {
        return new FcCompiledScript(this, parse(script));
    }

    @Override
    public CompiledScript compile(Reader reader) throws ScriptException {
        try {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[4_096];
            for (int n; (n = reader.read(buffer)) >= 0; ) {
                text.append(buffer, 0, n);
            }
            return compile(text.toString());
        } catch (IOException e) {
            throw new ScriptException("读取脚本失败：" + e.getMessage());
        }
    }

    @Override
    public ScriptEngineFactory getFactory() {
        return factory;
    }

    @Override
    public Bindings createBindings() {
        return new SimpleBindings();
    }

    /** 解析脚本；语法错误在这里暴露，便于 {@link #compile(String)} 提前失败。 */
    private List<Step> parse(String script) throws ScriptException {
        List<Step> steps = new ArrayList<>();
        String[] lines = script.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            for (String part : lines[i].split(";", -1)) {
                String line = part.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                steps.add(parseLine(line, i + 1, 0));
            }
        }
        return List.copyOf(steps);
    }

    private Step parseLine(String line, int lineNumber, int depth) throws ScriptException {
        if (depth > MAX_DEPTH) {
            throw error(lineNumber, "脚本嵌套过深（最多 " + MAX_DEPTH + " 层）");
        }
        int space = line.indexOf(' ');
        String head = (space < 0 ? line : line.substring(0, space))
                .toLowerCase(Locale.ROOT);
        String rest = space < 0 ? "" : line.substring(space + 1).strip();
        return switch (head) {
            case "echo" -> new EchoStep(rest, lineNumber);
            case "set" -> parseSet(rest, lineNumber);
            case "repeat" -> parseRepeat(rest, lineNumber, depth);
            default -> new CommandStep(line, lineNumber);
        };
    }

    private Step parseSet(String rest, int lineNumber) throws ScriptException {
        int space = rest.indexOf(' ');
        if (space <= 0) {
            throw error(lineNumber, "set 需要变量名与值");
        }
        String name = rest.substring(0, space);
        String value = rest.substring(space + 1).strip();
        if (name.startsWith("${") || name.contains("}")) {
            throw error(lineNumber, "变量名不合法：" + name);
        }
        return new SetStep(name, value, lineNumber);
    }

    private Step parseRepeat(String rest, int lineNumber, int depth) throws ScriptException {
        int space = rest.indexOf(' ');
        if (space <= 0 || rest.substring(space + 1).isBlank()) {
            throw error(lineNumber, "repeat 需要次数与一行脚本");
        }
        int times;
        try {
            times = Integer.parseInt(rest.substring(0, space));
        } catch (NumberFormatException e) {
            throw error(lineNumber, "repeat 次数必须是整数：" + rest.substring(0, space));
        }
        if (times < 0 || times > MAX_REPEAT) {
            throw error(lineNumber, "repeat 次数必须在 0–" + MAX_REPEAT + " 之间：" + times);
        }
        Step body = parseLine(rest.substring(space + 1).strip(), lineNumber, depth + 1);
        return new RepeatStep(times, body, lineNumber);
    }

    private String run(List<Step> steps, ScriptContext context) throws ScriptException {
        Bindings bindings = context.getBindings(ScriptContext.ENGINE_SCOPE);
        if (bindings == null) {
            bindings = new SimpleBindings();
            context.setBindings(bindings, ScriptContext.ENGINE_SCOPE);
        }
        StringBuilder out = new StringBuilder();
        for (Step step : steps) {
            execute(step, context, bindings, out);
        }
        return out.toString();
    }

    private void execute(Step step, ScriptContext context, Bindings bindings, StringBuilder out)
            throws ScriptException {
        switch (step) {
            case EchoStep echo -> append(out, substitute(echo.text(), bindings, echo.line()));
            case SetStep set -> bindings.put(set.name(), substitute(set.value(), bindings,
                    set.line()));
            case RepeatStep repeat -> {
                for (int i = 0; i < repeat.times(); i++) {
                    execute(repeat.body(), context, bindings, out);
                }
            }
            case CommandStep command -> {
                String resolved = substitute(command.text(), bindings, command.line());
                Object host = context.getAttribute(HOST, ScriptContext.ENGINE_SCOPE);
                if (!(host instanceof Host executor)) {
                    throw error(command.line(),
                            "未绑定执行宿主：绑定 " + HOST + " 必须是 " + Host.class.getName());
                }
                try {
                    String result = executor.execute(resolved);
                    bindings.put(LAST, result);
                    append(out, result);
                } catch (ScriptException e) {
                    throw e;
                } catch (Exception e) {
                    throw error(command.line(), "命令执行失败：" + e.getMessage());
                }
            }
        }
    }

    private String substitute(String text, Bindings bindings, int line) throws ScriptException {
        Matcher matcher = VARIABLE.matcher(text);
        StringBuilder resolved = new StringBuilder();
        int at = 0;
        while (matcher.find()) {
            resolved.append(text, at, matcher.start());
            String name = matcher.group(1).strip();
            Object value = bindings.get(name);
            if (value == null) {
                throw error(line, "未定义变量：" + name);
            }
            resolved.append(value);
            at = matcher.end();
        }
        resolved.append(text.substring(at));
        return resolved.toString();
    }

    private static void append(StringBuilder out, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (!out.isEmpty()) {
            out.append('\n');
        }
        out.append(text);
    }

    private static ScriptException error(int line, String message) {
        return new ScriptException(message, "frontier-script", line);
    }

    private sealed interface Step permits EchoStep, SetStep, RepeatStep, CommandStep {
        int line();
    }

    private record EchoStep(String text, int line) implements Step {
    }

    private record SetStep(String name, String value, int line) implements Step {
    }

    private record RepeatStep(int times, Step body, int line) implements Step {
    }

    private record CommandStep(String text, int line) implements Step {
    }

    /** 已通过语法检查的脚本；每次 {@code eval} 独立执行。 */
    private static final class FcCompiledScript extends CompiledScript {
        private final FcScriptEngine owner;
        private final List<Step> steps;

        FcCompiledScript(FcScriptEngine owner, List<Step> steps) {
            this.owner = owner;
            this.steps = steps;
        }

        @Override
        public Object eval(ScriptContext context) throws ScriptException {
            return owner.run(steps, context);
        }

        @Override
        public ScriptEngine getEngine() {
            return owner;
        }
    }
}
