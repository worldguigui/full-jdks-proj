package fc.config;

/**
 * 配置装载异常：配置坏值/缺失时的可读错误（含键名与定位）。
 */
public class ConfigException extends RuntimeException {

    public ConfigException(String key, String message) {
        super("配置错误 [" + key + "]: " + message);
    }

    public ConfigException(String message) {
        super(message);
    }
}
