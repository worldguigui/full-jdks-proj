package fc.config.world;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 配方：在某地点把输入资源加工成输出资源。
 *
 * <p>启动时**一次性扣除输入**（含作为能源的 fuel），产出在 {@code ticks} 天完成后入库。
 * 输入不足则无法开工——这让"给殖民地运燃料"成为真实需求。</p>
 */
public record RecipeDef(
        String id,
        String name,
        int ticks,
        long at,
        Map<String, BigDecimal> inputs,
        Map<String, BigDecimal> outputs) {

    public RecipeDef {
        inputs = Map.copyOf(inputs);
        outputs = Map.copyOf(outputs);
    }
}
