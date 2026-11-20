package fc.colony;

import fc.world.ColonyState;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.MoneyLedger;
import fc.world.ResourceKind;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 玩家殖民地操作：查看升级要求、执行升级。
 *
 * <p>升级消耗玩家在殖民地存储的货物与玩家账户的信用点；货物经权威账本消耗，
 * 信用点转入殖民地账户（资金守恒）。升级成功后等级 +1，并写入编年史。</p>
 */
public final class ColonyService {

    private ColonyService() {
    }

    /** 操作结果。 */
    public record Result(boolean ok, String message) {
    }

    /** 殖民地概况（CLI 展示）。 */
    public static String describe(WorldState state, Ids.LocationId id) {
        ColonyState c = state.colony(id);
        if (c == null) {
            return "该地点不是殖民地。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(nameOf(state, id)).append("｜等级 ").append(c.level())
                .append("｜人口 ").append(c.population())
                .append(c.playerOwned() ? "｜玩家所有" : "｜非玩家所有").append('\n');
        ColonyState.Upgrade next = c.nextUpgrade();
        if (next == null) {
            sb.append("  已达最高等级。");
            return sb.toString();
        }
        sb.append("  升级至 ").append(next.toLevel()).append(" 级需要：");
        for (Map.Entry<ResourceKind, BigDecimal> e : next.goods().entrySet()) {
            sb.append(e.getKey().displayName()).append(' ')
                    .append(e.getValue().stripTrailingZeros().toPlainString());
            BigDecimal have = state.goods(id, e.getKey());
            sb.append("（现有 ").append(have.stripTrailingZeros().toPlainString())
                    .append(have.compareTo(e.getValue()) >= 0 ? " ✓" : " ✗").append("） ");
        }
        BigDecimal credits = next.credits();
        sb.append("信用点 ").append(credits.stripTrailingZeros().toPlainString())
                .append("（现有 ").append(state.credits().stripTrailingZeros().toPlainString())
                .append(state.credits().compareTo(credits) >= 0 ? " ✓" : " ✗").append("）");
        return sb.toString();
    }

    /** 执行升级。 */
    public static Result upgrade(WorldState state, Ids.LocationId id) {
        ColonyState c = state.colony(id);
        if (c == null) {
            return new Result(false, "该地点不是殖民地。");
        }
        if (!c.playerOwned()) {
            return new Result(false, "只能升级玩家自己的殖民地。");
        }
        ColonyState.Upgrade next = c.nextUpgrade();
        if (next == null) {
            return new Result(false, "该殖民地已达最高等级或缺少升级配置。");
        }
        for (Map.Entry<ResourceKind, BigDecimal> e : next.goods().entrySet()) {
            BigDecimal have = state.goods(id, e.getKey());
            if (have.compareTo(e.getValue()) < 0) {
                return new Result(false, "物资不足：" + e.getKey().displayName()
                        + " 需 " + e.getValue().stripTrailingZeros().toPlainString()
                        + "，现有 " + have.stripTrailingZeros().toPlainString());
            }
        }
        if (state.credits().compareTo(next.credits()) < 0) {
            return new Result(false, "信用点不足：需 "
                    + next.credits().stripTrailingZeros().toPlainString()
                    + "，现有 " + state.credits().stripTrailingZeros().toPlainString());
        }

        for (Map.Entry<ResourceKind, BigDecimal> e : next.goods().entrySet()) {
            state.consume(id, e.getKey(), e.getValue(), "殖民地升级");
        }
        if (next.credits().signum() > 0) {
            state.payFromPlayer(MoneyLedger.locationAccount(id), next.credits(), "殖民地升级");
        }
        c.upgrade();
        state.chronicle().record(new GameEvent.ColonyUpgraded(state.now(), id, c.level()));
        return new Result(true, nameOf(state, id) + " 升级至 " + c.level() + " 级。");
    }

    private static String nameOf(WorldState state, Ids.LocationId id) {
        var loc = state.graph().get(id);
        return loc == null ? ("#" + id.id()) : loc.name();
    }
}
