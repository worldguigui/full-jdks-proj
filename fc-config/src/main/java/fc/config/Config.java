package fc.config;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

/**
 * 游戏配置（只读接口）。
 *
 * <p>机制数值表（配方/成本/事件权重/科技树）、用户偏好、本地化的统一读取入口。
 * 具体来源（Properties/XML）由实现类决定；M0 骨架提供 Properties 实现，M1 起 XML+XSD。</p>
 */
public interface Config {

    /** 读取字符串配置（缺失抛 ConfigException）。 */
    String required(String key);

    /** 读取字符串配置（缺失返回 default）。 */
    String get(String key, String defaultValue);

    /** 读取整数配置（坏值抛 ConfigException）。 */
    int getInt(String key);

    /** 读取整数配置（缺失返回 default）。 */
    int getInt(String key, int defaultValue);

    /** 读取 BigDecimal 配置（坏值抛 ConfigException）。 */
    BigDecimal getDecimal(String key);

    /** 读取布尔配置。 */
    boolean getBool(String key, boolean defaultValue);

    /** 是否含某配置键。 */
    boolean has(String key);

    /** 全部配置键。 */
    Set<String> keys();
}
