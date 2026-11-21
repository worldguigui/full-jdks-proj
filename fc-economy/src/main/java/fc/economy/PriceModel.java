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

    private static final BigDecimal MIN_FACTOR = new BigDecimal("0.3");
    private static final BigDecimal MAX_FACTOR = new BigDecimal("5");
    private static final java.math.MathContext MC = new java.math.MathContext(20);

    private PriceModel() {
    }

    /** 基准价（无价格冲击）。 */
    public static BigDecimal basePrice(ResourceDef def, BigDecimal stock) {
        return basePrice(def, stock, BigDecimal.ONE);
    }

    /** 基准价（含事件价格冲击乘数）。 */
    public static BigDecimal basePrice(ResourceDef def, BigDecimal stock, BigDecimal shock) {
        BigDecimal s = stock.max(BigDecimal.ONE);
        BigDecimal ratio = def.targetStock().divide(s, 12, RoundingMode.HALF_UP);
        BigDecimal factor = rationalPower(ratio, def.elasticity());
        factor = factor.max(MIN_FACTOR).min(MAX_FACTOR);
        BigDecimal supply = def.basePrice().multiply(factor);
        return supply.multiply(shock == null ? BigDecimal.ONE : shock)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 精确分数幂：{@code base^(p/q)} = q 次根(base^p)。
     *
     * <p>指数先按其十进制表示化为既约分数（如 1.5 → 3/2），
     * 先用 {@link BigDecimal#pow(int)} 求整数幂，再用牛顿迭代求 q 次根。
     * 这样定价完全脱离 {@code double}，同库存同配置得到同一结果。</p>
     */
    public static BigDecimal rationalPower(BigDecimal base, double exponent) {
        if (base.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (exponent == 0.0) {
            return BigDecimal.ONE;
        }
        BigDecimal e = BigDecimal.valueOf(exponent).stripTrailingZeros();
        int scale = Math.max(0, e.scale());
        java.math.BigInteger num = e.unscaledValue();
        java.math.BigInteger den = java.math.BigInteger.TEN.pow(scale);
        java.math.BigInteger gcd = num.gcd(den);
        num = num.divide(gcd);
        den = den.divide(gcd);

        boolean negative = num.signum() < 0;
        int p = num.abs().intValueExact();
        int q = den.intValueExact();
        BigDecimal powered;
        if (p == 0) {
            powered = BigDecimal.ONE;
        } else if (q == 1) {
            powered = base.pow(p);
        } else {
            powered = nthRoot(base.pow(p), q);
        }
        if (negative) {
            powered = BigDecimal.ONE.divide(powered, 12, RoundingMode.HALF_UP);
        }
        return powered;
    }

    /** 牛顿迭代求 n 次根（正数）。 */
    public static BigDecimal nthRoot(BigDecimal value, int n) {
        if (n <= 1 || value.compareTo(BigDecimal.ONE) == 0) {
            return value;
        }
        BigDecimal a = value;
        // 初值：以 double 估计，可把迭代次数压到个位数；最终精度由迭代收敛决定。
        BigDecimal x = BigDecimal.valueOf(Math.max(1e-9, Math.pow(a.doubleValue(), 1.0 / n)));
        BigDecimal nDec = BigDecimal.valueOf(n);
        for (int i = 0; i < 80; i++) {
            BigDecimal xPow = x.pow(n - 1);
            if (xPow.signum() == 0) {
                break;
            }
            BigDecimal next = x.multiply(nDec.subtract(BigDecimal.ONE))
                    .add(a.divide(xPow, MC)).divide(nDec, MC);
            if (next.subtract(x).abs().compareTo(new BigDecimal("1e-18")) < 0) {
                x = next;
                break;
            }
            x = next;
        }
        return x.setScale(12, RoundingMode.HALF_UP);
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
