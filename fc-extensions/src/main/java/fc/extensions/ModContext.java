package fc.extensions;

import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 激活 mod 时注入的运行上下文。
 *
 * @param home     mod 所在目录（解压目录或 JAR 的父目录）
 * @param settings 描述符中 {@code setting.} 前缀键去掉前缀后的只读设置
 * @param log      以 mod id 命名的日志器
 */
public record ModContext(Path home, Map<String, String> settings, Logger log) {

    public ModContext {
        settings = Map.copyOf(settings);
    }
}
