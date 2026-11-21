package fc.extensions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 命令宏：以文本保存的一串 CLI 命令，支持 {@code ${参数}} 占位符。
 *
 * <p>文件格式（UTF-8）：</p>
 * <pre>
 * # 注释，忽略空行
 * &#64;name: 开局侦察
 * map
 * sail ${ship} ${dest}
 * scan ${ship}
 * </pre>
 *
 * <p>宏本身不依赖运行时模块；由调用方把展开后的命令交给
 * {@link RuntimeSession#execute(String)} 之类的执行器，因此可同时用于
 * 交互脚本、回归测试与 mod。</p>
 */
public final class Macro {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");

    private final String name;
    private final List<String> steps;
    private final Map<String, String> metadata;

    private Macro(String name, List<String> steps, Map<String, String> metadata) {
        this.name = name;
        this.steps = List.copyOf(steps);
        this.metadata = Map.copyOf(metadata);
    }

    /** 解析宏文本。 */
    public static Macro parse(String text, String fallbackName) {
        if (text == null) {
            throw new IllegalArgumentException("宏文本不能为空");
        }
        String name = fallbackName == null ? "macro" : fallbackName;
        List<String> steps = new ArrayList<>();
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String raw : text.split("\\R", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("@")) {
                int colon = line.indexOf(':');
                if (colon > 1) {
                    metadata.put(line.substring(1, colon).strip(), line.substring(colon + 1).strip());
                }
                continue;
            }
            if (line.startsWith("name:")) {
                name = line.substring("name:".length()).strip();
                continue;
            }
            steps.add(line);
        }
        String declared = metadata.get("name");
        if (declared != null && !declared.isBlank()) {
            name = declared;
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("宏没有任何命令");
        }
        return new Macro(name, steps, metadata);
    }

    /** 从文件读取（文件名作为缺省宏名）。 */
    public static Macro load(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String fallback = file.getFileName().toString().replaceFirst("\\.[^.]*$", "");
        return parse(text, fallback);
    }

    /** 保存为 UTF-8 文本（带 {@code @name:} 头）。 */
    public void save(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("@name: ").append(name).append('\n');
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            if (!"name".equals(e.getKey())) {
                sb.append('@').append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
        }
        for (String step : steps) {
            sb.append(step).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    /** 展开占位符；缺少参数时抛出 IllegalArgumentException 并指出参数名。 */
    public List<String> expand(Map<String, String> params) {
        Map<String, String> values = params == null ? Map.of() : params;
        List<String> out = new ArrayList<>(steps.size());
        for (String step : steps) {
            Matcher m = PLACEHOLDER.matcher(step);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String key = m.group(1);
                String value = values.get(key);
                if (value == null) {
                    throw new IllegalArgumentException("宏 " + name + " 缺少参数: " + key);
                }
                if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                    throw new IllegalArgumentException("参数 " + key + " 不能包含换行");
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(value));
            }
            m.appendTail(sb);
            out.add(sb.toString());
        }
        return List.copyOf(out);
    }

    /** 逐个执行展开后的命令。 */
    public List<String> run(Map<String, String> params, java.util.function.Function<String, String> executor) {
        List<String> outputs = new ArrayList<>();
        for (String command : expand(params)) {
            outputs.add(executor.apply(command));
        }
        return List.copyOf(outputs);
    }

    public String name() {
        return name;
    }

    public List<String> steps() {
        return steps;
    }

    public Map<String, String> metadata() {
        return metadata;
    }
}
