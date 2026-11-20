package fc.economy;

import fc.world.Ids;
import fc.world.Location;
import fc.world.LocationOps;
import fc.world.Stock;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * 玩家交易服务：在舰船所在地点买卖资源。
 *
 * <p>守恒：货物在地点库存与船货舱之间转移（世界总量不变）；信用点在玩家与地点之间转移
 * （同样不变）。因此交易既不凭空造货也不凭空造钱，只改变分布与价格。</p>
 *
 * <p>买价 = 基准价 × 1.05，卖价 = 基准价 × 0.95，价差构成交易成本。</p>
 */
public final class TradeService {

    private final Economy economy;

    public TradeService(Economy economy) {
        this.economy = economy;
    }

    /** 交易结果。 */
    public record Result(boolean ok, String message, BigDecimal cashDelta, BigDecimal unitPrice) {
    }

    /** 报价：某舰船所在地点的买入/卖出价。 */
    public String quote(WorldState state, Location.Ship ship, String resource) {
        Ids.LocationId at = ship.parent();
        Location loc = state.graph().get(at);
        if (loc == null) {
            return "舰船所在地点未知。";
        }
        var buy = economy.buyPrice(state, at, resource);
        var sell = economy.sellPrice(state, at, resource);
        if (buy.isEmpty()) {
            return "该世界未配置资源 " + resource + " 的价格。";
        }
        BigDecimal stock = loc.stock().amount(resource);
        String mine = ship.cargo().amount(resource).stripTrailingZeros().toPlainString();
        return String.format("%s @ %s｜库存 %s｜买入 %s｜卖出 %s｜货舱 %s｜信用点 %s",
                resource, loc.name(),
                stock.stripTrailingZeros().toPlainString(),
                buy.get().toPlainString(), sell.get().toPlainString(),
                mine, state.credits().stripTrailingZeros().toPlainString());
    }

    /** 买入：玩家付钱，货进船。 */
    public Result buy(WorldState state, Location.Ship ship, String resource, int qty) {
        if (qty <= 0) {
            return new Result(false, "数量必须为正", BigDecimal.ZERO, BigDecimal.ZERO);
        }
        Ids.LocationId at = ship.parent();
        Location loc = state.graph().get(at);
        if (loc == null) {
            return new Result(false, "舰船所在地点未知", BigDecimal.ZERO, BigDecimal.ZERO);
        }
        Optional<BigDecimal> priceOpt = economy.buyPrice(state, at, resource);
        if (priceOpt.isEmpty()) {
            return new Result(false, "该世界未配置资源 " + resource, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal price = priceOpt.get();

        // 按可用量部分成交：库存上限、资金上限
        int byStock = loc.stock().amount(resource).intValue();
        int byCash = price.signum() > 0
                ? state.credits().divide(price, 0, java.math.RoundingMode.DOWN).intValue()
                : Integer.MAX_VALUE;
        int fill = Math.min(qty, Math.min(byStock, byCash));
        if (fill <= 0) {
            String why = byStock <= 0 ? "本地库存为 0" : "信用点不足（现有 "
                    + state.credits().stripTrailingZeros().toPlainString() + "）";
            return new Result(false, "买入失败：" + why, BigDecimal.ZERO, price);
        }
        String note = fill < qty ? "（请求 " + qty + "，按可用量成交 " + fill + "）" : "";
        qty = fill;
        BigDecimal cost = price.multiply(BigDecimal.valueOf(qty));

        // 货物：地点 → 船
        Stock locStock = loc.stock().subtract(resource, BigDecimal.valueOf(qty));
        state.graph().replace(LocationOps.withStock(loc, locStock));
        ship.setCargo(ship.cargo().add(resource, BigDecimal.valueOf(qty)));

        // 资金：玩家 → 地点
        state.addCredits(cost.negate());
        Stock locCredits = locStock.add("credits", cost);
        state.graph().replace(LocationOps.withStock(loc, locCredits));

        return new Result(true, "买入 " + qty + " " + resource + " @ " + price.toPlainString()
                + "，共 " + cost.toPlainString() + "；信用点余额 "
                + state.credits().stripTrailingZeros().toPlainString() + note, cost.negate(), price);
    }

    /** 卖出：货出船，玩家收钱。 */
    public Result sell(WorldState state, Location.Ship ship, String resource, int qty) {
        if (qty <= 0) {
            return new Result(false, "数量必须为正", BigDecimal.ZERO, BigDecimal.ZERO);
        }
        Ids.LocationId at = ship.parent();
        Location loc = state.graph().get(at);
        if (loc == null) {
            return new Result(false, "舰船所在地点未知", BigDecimal.ZERO, BigDecimal.ZERO);
        }
        Optional<BigDecimal> priceOpt = economy.sellPrice(state, at, resource);
        if (priceOpt.isEmpty()) {
            return new Result(false, "该世界未配置资源 " + resource, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal price = priceOpt.get();

        // 按可用量部分成交：货舱上限、买方资金上限
        int byCargo = ship.cargo().amount(resource).intValue();
        BigDecimal creditsAtLoc = loc.stock().amount("credits");
        int byBuyer = price.signum() > 0
                ? creditsAtLoc.divide(price, 0, java.math.RoundingMode.DOWN).intValue()
                : Integer.MAX_VALUE;
        int fill = Math.min(qty, Math.min(byCargo, byBuyer));
        if (fill <= 0) {
            String why = byCargo <= 0 ? "货舱没有该资源" : "该地点信用点不足（现有 "
                    + creditsAtLoc.stripTrailingZeros().toPlainString() + "）";
            return new Result(false, "卖出失败：" + why, BigDecimal.ZERO, price);
        }
        String note = fill < qty ? "（请求 " + qty + "，按可用量成交 " + fill + "）" : "";
        qty = fill;
        BigDecimal revenue = price.multiply(BigDecimal.valueOf(qty));

        // 货物：船 → 地点
        ship.setCargo(ship.cargo().subtract(resource, BigDecimal.valueOf(qty)));
        Stock locStock = loc.stock().add(resource, BigDecimal.valueOf(qty));
        // 资金：地点 → 玩家
        locStock = locStock.subtract("credits", revenue);
        state.graph().replace(LocationOps.withStock(loc, locStock));
        state.addCredits(revenue);

        return new Result(true, "卖出 " + qty + " " + resource + " @ " + price.toPlainString()
                + "，收入 " + revenue.toPlainString() + "；信用点余额 "
                + state.credits().stripTrailingZeros().toPlainString() + note, revenue, price);
    }

    /** 地点当前报价表（CLI 展示用）。 */
    public String marketTable(WorldState state, Location.Ship ship) {
        Ids.LocationId at = ship.parent();
        Location loc = state.graph().get(at);
        if (loc == null) {
            return "舰船所在地点未知。";
        }
        StringBuilder sb = new StringBuilder("---- ").append(loc.name())
                .append(" 行情（信用点 ").append(state.credits().stripTrailingZeros().toPlainString())
                .append("）----\n");
        sb.append(String.format("  %-8s %8s %8s %8s%n", "资源", "库存", "买入", "卖出"));
        for (Map.Entry<String, fc.config.world.ResourceDef> e : economy.resources().entrySet()) {
            String res = e.getKey();
            var buy = economy.buyPrice(state, at, res);
            var sell = economy.sellPrice(state, at, res);
            if (buy.isEmpty() || sell.isEmpty()) {
                continue;
            }
            sb.append(String.format("  %-8s %8s %8s %8s%n", res,
                    loc.stock().amount(res).stripTrailingZeros().toPlainString(),
                    buy.get().toPlainString(), sell.get().toPlainString()));
        }
        return sb.toString();
    }
}
