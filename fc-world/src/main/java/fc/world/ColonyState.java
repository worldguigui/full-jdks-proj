package fc.world;

import fc.world.Ids.LocationId;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 殖民地状态：等级、人口与升级要求。
 *
 * <p>等级 0 为前哨站，1–3 为殖民地。升级要求（货物 + 信用点）来自配置，
 * 核心代码不硬编码具体数值。殖民地与星域使用同一套 {@link Location}、
 * {@link Agent}、{@link Stock} 规则，不存在第二套殖民模拟器。</p>
 */
public final class ColonyState {

    public static final int MAX_LEVEL = 3;

    /** 一次升级的物资与资金要求。 */
    public record Upgrade(int toLevel, Map<ResourceKind, BigDecimal> goods, BigDecimal credits) {
        public Upgrade {
            goods = Map.copyOf(goods);
        }
    }

    private final LocationId id;
    private final boolean playerOwned;
    private final List<Upgrade> upgrades;
    private int level;
    private int population;

    public ColonyState(LocationId id, boolean playerOwned, int level, int population,
                       List<Upgrade> upgrades) {
        if (level < 0 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("殖民地等级越界: " + level);
        }
        this.id = id;
        this.playerOwned = playerOwned;
        this.level = level;
        this.population = Math.max(0, population);
        this.upgrades = List.copyOf(upgrades);
    }

    public LocationId id() {
        return id;
    }

    public boolean playerOwned() {
        return playerOwned;
    }

    public int level() {
        return level;
    }

    public int population() {
        return population;
    }

    public List<Upgrade> upgrades() {
        return upgrades;
    }

    /** 下一级升级要求；已满级返回 null。 */
    public Upgrade nextUpgrade() {
        for (Upgrade u : upgrades) {
            if (u.toLevel() == level + 1) {
                return u;
            }
        }
        return null;
    }

    /** 是否已是殖民地（等级 ≥ 1）。 */
    public boolean isColony() {
        return level >= 1;
    }

    public void upgrade() {
        if (level >= MAX_LEVEL) {
            throw new IllegalStateException("殖民地已达最高等级: " + id);
        }
        level++;
    }

    public void setPopulation(int population) {
        this.population = Math.max(0, population);
    }
}
