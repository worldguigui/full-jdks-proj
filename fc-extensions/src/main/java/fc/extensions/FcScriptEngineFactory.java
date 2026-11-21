package fc.extensions;

import java.util.List;
import java.util.Locale;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;

/**
 * Frontier Script 的 {@link ScriptEngineFactory}。
 *
 * <p>通过 {@code META-INF/services/javax.script.ScriptEngineFactory} 注册，
 * {@code new ScriptEngineManager().getEngineByName("frontier")} 即可发现；
 * 工厂只描述语言能力，不执行脚本。</p>
 */
public final class FcScriptEngineFactory implements ScriptEngineFactory {

    /** 语言主名。 */
    public static final String ENGINE_NAME = "Frontier Script";

    @Override
    public String getEngineName() {
        return ENGINE_NAME;
    }

    @Override
    public String getEngineVersion() {
        return "1.0";
    }

    @Override
    public List<String> getExtensions() {
        return List.of("fcs");
    }

    @Override
    public List<String> getMimeTypes() {
        return List.of("text/x-frontier-script");
    }

    @Override
    public List<String> getNames() {
        return List.of("frontier", "fcs");
    }

    @Override
    public String getLanguageName() {
        return "Frontier Script";
    }

    @Override
    public String getLanguageVersion() {
        return "1.0";
    }

    @Override
    public Object getParameter(String key) {
        return switch (key) {
            case ScriptEngine.NAME -> "frontier";
            case ScriptEngine.ENGINE -> getEngineName();
            case ScriptEngine.ENGINE_VERSION -> getEngineVersion();
            case ScriptEngine.LANGUAGE -> getLanguageName();
            case ScriptEngine.LANGUAGE_VERSION -> getLanguageVersion();
            default -> null;
        };
    }

    @Override
    public String getMethodCallSyntax(String obj, String method, String... args) {
        StringBuilder call = new StringBuilder(String.valueOf(obj)).append('.').append(method)
                .append('(');
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                call.append(", ");
            }
            call.append(args[i]);
        }
        return call.append(')').toString();
    }

    @Override
    public String getOutputStatement(String toDisplay) {
        return "echo " + toDisplay;
    }

    @Override
    public String getProgram(String... statements) {
        return String.join("\n", statements);
    }

    @Override
    public ScriptEngine getScriptEngine() {
        FcScriptEngine engine = new FcScriptEngine(this);
        engine.put(ScriptEngine.FILENAME, "frontier-script");
        engine.put("locale", Locale.ROOT.toLanguageTag());
        return engine;
    }
}
