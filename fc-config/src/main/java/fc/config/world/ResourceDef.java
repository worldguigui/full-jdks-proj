package fc.config.world;

import java.math.BigDecimal;

/**
 * 资源经济参数：基准价、目标库存、价格弹性。
 *
 * <p>定价模型（{@code fc-economy.PriceModel}）：
 * <pre>价格 = basePrice × (targetStock / max(stock,1)) ^ elasticity</pre>
 * 库存低于目标 → 价格上涨；库存充足 → 价格回落。弹性越大越敏感。</p>
 */
public record ResourceDef(String name, BigDecimal basePrice, BigDecimal targetStock, double elasticity) {
}
