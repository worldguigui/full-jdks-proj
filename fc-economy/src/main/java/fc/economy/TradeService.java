package fc.economy;

import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Location;
import fc.world.MoneyLedger;
import fc.world.ResourceKind;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * 玩家交易服务：在舰船所在地点买卖资源。
 *
 * <p>守恒：货物在地点库存与船货舱之间转移（权威账本的在途/转移结算，世界总量不变）；
 * 信用点在玩家账户与地点账户之间转移（同样不变）。交易既不凭空造货也不凭空造钱，
 * 只改变分布并影响价格。</p>
 *
 * <p>买价 = 基准价 × 1.05，卖价 = 基准价 × 0.95，价差构成交易成本。
 * 事件造成的价格冲击会临时放大买卖价。</p>
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
    public String quote(WorldState state, Location.Ship ship, ResourceKind kind) {
        Ids.LocationId at = ship.parent();
        Location loc = state.graph().get(at);
        if (loc == null) {
            return "舰船所在地点未知。";
        }
        var buy = economy.buyPrice(state, at, kind);
        var sell = economy.sellPrice(state, at, kind);
        if (buy.isEmpty()) {
            return "该世界未配置资源 " + kind.id() + " 的价格。";
        }
        BigDecimal stock = state.goods(at, kind);
        String mine = ship.cargo().amount(kind).stripTrailingZeros().toPlainString();
        BigDecimal tax = state.taxRateAt(at);
        return String.format("%s @ %s｜库存 %s｜买入 %s｜卖出 %s｜税 %s｜货舱 %s｜信用点 %s",
                kind.displayName(), loc.name(),
                stock.stripTrailingZeros().toPlainString(),
                effectiveBuy(state, ship, at, buy.get()).toPlainString(),
                effectiveSell(state, ship, at, sell.get()).toPlainString(),
                tax.stripTrailingZeros().toPlainString(),
                mine, state.credits().stripTrailingZeros().toPlainString());
    }

    /** 贸易技能带来买卖价改善：每点 0.5%，最多 5%。 */
    private static BigDecimal skillFactor(WorldState state, Location.Ship ship, boolean buying) {
        int skill = 0;
        for (fc.world.Agent a : state.agentsAt(ship.id())) {
            skill = Math.max(skill, a.skills().get(fc.world.SkillSet.Skill.TRADE));
        }
        BigDecimal shift = new BigDecimal("0.005").multiply(BigDecimal.valueOf(skill));
        return buying ? BigDecimal.ONE.subtract(shift) : BigDecimal.ONE.add(shift);
    }

    /** 含技能改善与本地税的买入单价。 */
    private static BigDecimal effectiveBuy(WorldState state, Location.Ship ship,
                                           Ids.LocationId at, BigDecimal quoted) {
        BigDecimal price = quoted.multiply(skillFactor(state, ship, true))
                .setScale(2, RoundingMode.HALF_UP);
        return price.multiply(BigDecimal.ONE.add(state.taxRateAt(at)))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** 含技能改善与本地税的卖出单价（税由买方支付、进入势力账户）。 */
    private static BigDecimal effectiveSell(WorldState state, Location.Ship ship,
                                            Ids.LocationId at, BigDecimal quoted) {
        BigDecimal price = quoted.multiply(skillFactor(state, ship, false))
                .setScale(2, RoundingMode.HALF_UP);
        return price.multiply(BigDecimal.ONE.subtract(state.taxRateAt(at)))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** 税款接收账户：地点归属势力的账户，无主则进入国库。 */
    private static String taxAccount(WorldState state, Ids.LocationId at) {
        Ids.FactionId owner = state.locationOwner(at);
        return owner == null ? MoneyLedger.TREASURY : MoneyLedger.factionAccount(owner);
    }

    /** 买入：玩家付钱，货进船。 */
    public Result buy(WorldState state, Location.Ship ship, ResourceKind kind, int qty) {
        if (qty <= 0) {
            return new Result(false, "数量必须为正", BigDecimal.ZERO, BigDecimal.ZERO);
        }
        Ids.LocationId at = ship.parent();
        Location loc = state.graph().get(at);
        if (loc == null) {
            return new Result(false, "舰船所在地点未知", BigDecimal.ZERO, BigDecimal.ZERO);
        }
        Optional<BigDecimal> priceOpt = economy.buyPrice(state, at, kind);
        if (priceOpt.isEmpty()) {
            return new Result(false, "该世界未配置资源 " + kind.id(), BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal price = effectiveBuy(state, ship, at, priceOpt.get());

        int byStock = state.goods(at, kind).intValue();
        int byCash = price.signum() > 0
                ? state.credits().divide(price, 0, RoundingMode.DOWN).intValue()
                : Integer.MAX_VALUE;
        int byCargo = ship.cargoFree().intValue();
        int fill = Math.min(qty, Math.min(byStock, Math.min(byCash, byCargo)));
        if (fill <= 0) {
            String why = byStock <= 0 ? "本地库存为 0"
                    : byCargo <= 0 ? "货舱已满"
                    : "信用点不足（现有 "
                            + state.credits().stripTrailingZeros().toPlainString() + "）";
            return new Result(false, "买入失败：" + why, BigDecimal.ZERO, price);
        }
        String note = fill < qty ? "（请求 " + qty + "，按可用量成交 " + fill + "）" : "";
        qty = fill;
        BigDecimal cost = price.multiply(BigDecimal.valueOf(qty));

        state.transferStock(at, ship.id(), kind, BigDecimal.valueOf(qty), "市场买入");
        BigDecimal tax = cost.multiply(state.taxRateAt(at))
                .divide(BigDecimal.ONE.add(state.taxRateAt(at)), 2, RoundingMode.HALF_UP);
        if (tax.signum() > 0) {
            state.payFromPlayer(taxAccount(state, at), tax, "交易税 " + kind.id());
        }
        state.payFromPlayer(MoneyLedger.locationAccount(at), cost.subtract(tax),
                "买入 " + kind.id());

        state.chronicle().record(new GameEvent.TradeSettled(
                state.now(), at, kind, BigDecimal.valueOf(qty), price, "buy"));
        return new Result(true, "买入 " + qty + " " + kind.displayName() + " @ " + price.toPlainString()
                + "，共 " + cost.toPlainString() + "；信用点余额 "
                + state.credits().stripTrailingZeros().toPlainString() + note, cost.negate(), price);
    }

    /** 卖出：货出船，玩家收钱。 */
    public Result sell(WorldState state, Location.Ship ship, ResourceKind kind, int qty) {
        if (qty <= 0) {
            return new Result(false, "数量必须为正", BigDecimal.ZERO, BigDecimal.ZERO);
        }
        Ids.LocationId at = ship.parent();
        Location loc = state.graph().get(at);
        if (loc == null) {
            return new Result(false, "舰船所在地点未知", BigDecimal.ZERO, BigDecimal.ZERO);
        }
        Optional<BigDecimal> priceOpt = economy.sellPrice(state, at, kind);
        if (priceOpt.isEmpty()) {
            return new Result(false, "该世界未配置资源 " + kind.id(), BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal quoted = priceOpt.get()
                .multiply(skillFactor(state, ship, false))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal rate = state.taxRateAt(at);
        BigDecimal price = quoted.multiply(BigDecimal.ONE.subtract(rate))
                .setScale(2, RoundingMode.HALF_UP);

        int byCargo = ship.cargo().amount(kind).intValue();
        String buyer = MoneyLedger.locationAccount(at);
        BigDecimal locCredits = state.money().balance(buyer);
        int byBuyer = quoted.signum() > 0
                ? locCredits.divide(quoted, 0, RoundingMode.DOWN).intValue()
                : Integer.MAX_VALUE;
        int fill = Math.min(qty, Math.min(byCargo, byBuyer));
        if (fill <= 0) {
            String why = byCargo <= 0 ? "货舱没有该资源" : "该地点信用点不足（现有 "
                    + locCredits.stripTrailingZeros().toPlainString() + "）";
            return new Result(false, "卖出失败：" + why, BigDecimal.ZERO, price);
        }
        String note = fill < qty ? "（请求 " + qty + "，按可用量成交 " + fill + "）" : "";
        qty = fill;
        BigDecimal gross = quoted.multiply(BigDecimal.valueOf(qty));
        BigDecimal revenue = price.multiply(BigDecimal.valueOf(qty));
        BigDecimal tax = gross.subtract(revenue);

        state.transferStock(ship.id(), at, kind, BigDecimal.valueOf(qty), "市场卖出");
        state.money().transfer(state.now(), buyer, MoneyLedger.PLAYER, revenue, "卖出 " + kind.id());
        if (tax.signum() > 0) {
            state.money().transfer(state.now(), buyer, taxAccount(state, at), tax,
                    "交易税 " + kind.id());
        }

        state.chronicle().record(new GameEvent.TradeSettled(
                state.now(), at, kind, BigDecimal.valueOf(qty), price, "sell"));
        return new Result(true, "卖出 " + qty + " " + kind.displayName() + " @ " + price.toPlainString()
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
                .append("，税率 ").append(state.taxRateAt(at).stripTrailingZeros().toPlainString())
                .append("）----\n");
        sb.append(String.format("  %-10s %8s %8s %8s%n", "资源", "库存", "买入", "卖出"));
        for (Map.Entry<ResourceKind, fc.config.world.ResourceDef> e : economy.resources().entrySet()) {
            ResourceKind kind = e.getKey();
            var buy = economy.buyPrice(state, at, kind);
            var sell = economy.sellPrice(state, at, kind);
            if (buy.isEmpty() || sell.isEmpty()) {
                continue;
            }
            sb.append(String.format("  %-10s %8s %8s %8s%n", kind.displayName(),
                    state.goods(at, kind).stripTrailingZeros().toPlainString(),
                    effectiveBuy(state, ship, at, buy.get()).toPlainString(),
                    effectiveSell(state, ship, at, sell.get()).toPlainString()));
        }
        return sb.toString();
    }
}
