package fc.world;

import fc.world.Ids.LocationId;
import fc.world.Ids.ShipId;

import java.math.BigDecimal;

/**
 * 测绘任务：由一艘舰船对某个 DETECTED/SURVEYED 地点进行勘测。
 *
 * <p>进度按船上最高探索技能的工作倍率推进；完成后地点变为 CHARTED，
 * 并产出科研数据。任务状态进入状态哈希，保证重放一致。</p>
 */
public final class SurveyTask {

    private final LocationId target;
    private final ShipId ship;
    private final int totalDays;
    private BigDecimal progress;
    private int researchYield;

    public SurveyTask(LocationId target, ShipId ship, int totalDays) {
        this.target = target;
        this.ship = ship;
        this.totalDays = Math.max(1, totalDays);
        this.progress = BigDecimal.ZERO;
    }

    public LocationId target() {
        return target;
    }

    public ShipId ship() {
        return ship;
    }

    public int totalDays() {
        return totalDays;
    }

    public BigDecimal progress() {
        return progress;
    }

    public int researchYield() {
        return researchYield;
    }

    public void setResearchYield(int researchYield) {
        this.researchYield = Math.max(0, researchYield);
    }

    public boolean done() {
        return progress.compareTo(BigDecimal.valueOf(totalDays)) >= 0;
    }

    public void advance(BigDecimal amount) {
        progress = progress.add(amount).min(BigDecimal.valueOf(totalDays));
    }
}
