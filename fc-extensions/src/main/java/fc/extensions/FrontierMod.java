package fc.extensions;

/**
 * Mod 扩展点（SPI）。
 *
 * <p>mod 以独立 JAR 或解压目录提供 {@code mod.properties} 与一个实现本接口的类。
 * 加载器在完成描述符校验、可选签名校验之后调用 {@link #activate(ModContext)}；
 * 卸载（进程退出或显式关闭）时调用 {@link #deactivate()}。</p>
 *
 * <p>接口只暴露稳定的最小契约：核心 sealed 模型（{@code Location}/{@code Link}/
 * {@code GameEvent}）不允许 mod 新增子类型，mod 只能通过配置与已有服务扩展内容。</p>
 */
public interface FrontierMod {

    /** 当前 mod API 版本；描述符中的 apiVersion 必须与之相等。 */
    int API_VERSION = 1;

    /** 激活 mod（加载完成后调用一次）。 */
    void activate(ModContext context) throws Exception;

    /** 停用 mod；默认无操作。 */
    default void deactivate() {
    }
}
