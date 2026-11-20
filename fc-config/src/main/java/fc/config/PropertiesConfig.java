package fc.config;

import java.math.BigDecimal;
import java.util.Properties;
import java.util.Set;

/**
 * Properties 配置实现。
 *
 * <p>从 {@code .properties} 资源装载配置，提供强类型读取（int/BigDecimal/bool），
 * 坏值或缺失时抛 {@link ConfigException}（带键名定位），满足「配置坏值能报错」的 M0 验收。</p>
 */
public final class PropertiesConfig implements Config {

    private final Properties props;

    private PropertiesConfig(Properties props) {
        this.props = props;
    }

    /** 从 Properties 构造。 */
    public static PropertiesConfig of(Properties props) {
        return new PropertiesConfig(new Properties(props));
    }

    /** 从文件路径或类路径资源装载（缺省空配置）。 */
    public static PropertiesConfig load(String path) {
        Properties p = new Properties();
        // 先按文件路径尝试，再按类路径资源尝试
        java.nio.file.Path file = java.nio.file.Path.of(path);
        if (java.nio.file.Files.exists(file)) {
            try (var in = java.nio.file.Files.newInputStream(file)) {
                p.load(in);
                return new PropertiesConfig(p);
            } catch (Exception e) {
                throw new ConfigException("无法读取配置文件: " + path + " (" + e + ")");
            }
        }
        try (var in = PropertiesConfig.class.getClassLoader().getResourceAsStream(path)) {
            if (in != null) {
                p.load(in);
            }
        } catch (Exception e) {
            throw new ConfigException("无法装载配置资源: " + path + " (" + e + ")");
        }
        return new PropertiesConfig(p);
    }

    @Override
    public String required(String key) {
        String v = props.getProperty(key);
        if (v == null) {
            throw new ConfigException(key, "缺失");
        }
        return v.trim();
    }

    @Override
    public String get(String key, String defaultValue) {
        String v = props.getProperty(key);
        return v == null ? defaultValue : v.trim();
    }

    @Override
    public int getInt(String key) {
        return parse(key, required(key), Integer::parseInt);
    }

    @Override
    public int getInt(String key, int defaultValue) {
        if (!has(key)) {
            return defaultValue;
        }
        return getInt(key);
    }

    @Override
    public BigDecimal getDecimal(String key) {
        return parse(key, required(key), BigDecimal::new);
    }

    @Override
    public boolean getBool(String key, boolean defaultValue) {
        if (!has(key)) {
            return defaultValue;
        }
        String v = required(key);
        if ("true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v) || "1".equals(v)) {
            return true;
        }
        if ("false".equalsIgnoreCase(v) || "no".equalsIgnoreCase(v) || "0".equals(v)) {
            return false;
        }
        throw new ConfigException(key, "非法布尔值: " + v);
    }

    @Override
    public boolean has(String key) {
        return props.containsKey(key);
    }

    @Override
    public Set<String> keys() {
        return props.stringPropertyNames();
    }

    private <T> T parse(String key, String raw, Parser<T> parser) {
        try {
            return parser.parse(raw);
        } catch (RuntimeException e) {
            throw new ConfigException(key, "非法值: \"" + raw + "\" (" + e + ")");
        }
    }

    @FunctionalInterface
    private interface Parser<T> {
        T parse(String raw) throws RuntimeException;
    }
}
