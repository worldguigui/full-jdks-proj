package fc.extensions;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * {@code mod.properties} 的不可变视图。
 *
 * <p>必需键：{@code id}、{@code name}、{@code version}、{@code mainClass}、
 * {@code apiVersion}；可选键：{@code requireSignature}（默认 false）与任意
 * {@code setting.*}（进入 {@link ModContext#settings()}）。</p>
 *
 * @param id               全局唯一 id（小写字母开头，允许小写字母/数字/点/下划线/短横线）
 * @param name             显示名
 * @param version          语义化版本 {@code MAJOR.MINOR.PATCH[-qualifier]}
 * @param mainClass        实现 {@link FrontierMod} 的类名
 * @param apiVersion       mod API 版本，必须等于 {@link FrontierMod#API_VERSION}
 * @param requireSignature 是否要求 JAR 带有效签名
 * @param settings         设置项（已去掉 {@code setting.} 前缀）
 */
public record ModDescriptor(String id, String name, String version, String mainClass,
                            int apiVersion, boolean requireSignature,
                            Map<String, String> settings) {

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9._-]{1,63}");
    private static final Pattern VERSION =
            Pattern.compile("\\d+\\.\\d+\\.\\d+(?:-[A-Za-z0-9.]+)?");
    private static final Pattern CLASS =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    public ModDescriptor {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("mod id 非法: " + id
                    + "（小写字母开头，2–64 位字母/数字/点/下划线/短横线）");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("mod name 不能为空");
        }
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("mod version 必须是 MAJOR.MINOR.PATCH[-qualifier]: "
                    + version);
        }
        if (mainClass == null || !CLASS.matcher(mainClass).matches()) {
            throw new IllegalArgumentException("mod mainClass 非法: " + mainClass);
        }
        if (apiVersion != FrontierMod.API_VERSION) {
            throw new IllegalArgumentException("mod API 版本不兼容: 需要 "
                    + FrontierMod.API_VERSION + "，实际 " + apiVersion);
        }
        settings = Map.copyOf(settings);
    }

    /** 从描述符属性解析；缺失/非法键转换为 ModException。 */
    public static ModDescriptor from(Properties props) throws ModException {
        try {
            Map<String, String> settings = new LinkedHashMap<>();
            for (String key : props.stringPropertyNames()) {
                if (key.startsWith("setting.")) {
                    settings.put(key.substring("setting.".length()), props.getProperty(key));
                }
            }
            return new ModDescriptor(
                    required(props, "id"),
                    required(props, "name"),
                    required(props, "version"),
                    required(props, "mainClass"),
                    Integer.parseInt(props.getProperty("apiVersion",
                            Integer.toString(FrontierMod.API_VERSION)).strip()),
                    Boolean.parseBoolean(props.getProperty("requireSignature", "false").strip()),
                    settings);
        } catch (IllegalArgumentException e) {
            throw new ModException("mod 描述符非法: " + e.getMessage(), e);
        }
    }

    private static String required(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少必需键 " + key);
        }
        return value.strip();
    }
}
