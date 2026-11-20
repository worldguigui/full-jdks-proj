package fc.economy;

import fc.config.world.ResourceDef;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 定价模型：库存相对目标越少，价格越高（幂函数弹性），并带买卖价差与事件冲击。
 *
 * <pre>
 * 基准价 = basePrice × (targetStock / max(stock,1)) ^ elasticity × 冲击乘数
 *        （供需因子夹在 0.3×..5× 基准价）
 * 玩家买入 = 基准价 × 1.05
 * 玩家卖出 = 基准价 × 0.95
 * </pre>
 *
 * <p>因此"低买高卖"是有空间的，但价差 10% 构成交易成本，逼迫玩家寻找真实价差。</p>
 */
public final class PriceModel {

    /** 玩家买入加价（做市商价差）。 */
    public static final BigDecimal BUY_MARKUP = new BigDecimal("1.05");
    /** 玩家卖出折价。 */
    public static final BigDecimal SELL_DISCOUNT = new BigDecimal("0.95");

    private static final double MIN_FACTOR = 0.3;
    private static final double MAX_FACTOR = 5.0;

    private PriceModel() {
    }

    /** 基准价（无价格冲击）。 */
    public static BigDecimal basePrice(ResourceDef def, BigDecimal stock) {
        return basePrice(def, stock, BigDecimal.ONE);
    }

    /** 基准价（含事件价格冲击乘数）。 */
    public static BigDecimal basePrice(ResourceDef def, BigDecimal stock, BigDecimal shock) {
        double target = def.targetStock().doubleValue();
        double s = Math.max(1.0, stock.doubleValue());
        double factor = Math.pow(target / s, def.elasticity());
        factor = Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, factor));
        BigDecimal supply = def.basePrice().multiply(BigDecimal.valueOf(factor));
        return supply.multiply(shock == null ? BigDecimal.ONE : shock)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** 玩家买入价（无价格冲击）。 */
    public static BigDecimal buyPrice(ResourceDef def, BigDecimal stock) {
        return buyPrice(def, stock, BigDecimal.ONE);
    }

    /** 玩家买入价（含事件价格冲击乘数）。 */
    public static BigDecimal buyPrice(ResourceDef def, BigDecimal stock, BigDecimal shock) {
        return basePrice(def, stock, shock).multiply(BUY_MARKUP)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** 玩家卖出价（无价格冲击）。 */
    public static BigDecimal sellPrice(ResourceDef def, BigDecimal stock) {
        return sellPrice(def, stock, BigDecimal.ONE);
    }

    /** 玩家卖出价（含事件价格冲击乘数）。 */
    public static BigDecimal sellPrice(ResourceDef def, BigDecimal stock, BigDecimal shock) {
        BigDecimal p = basePrice(def, stock, shock).multiply(SELL_DISCOUNT)
                .setScale(2, RoundingMode.HALF_UP);
        return p.signum() <= 0 ? new BigDecimal("0.01") : p;
    }
}
