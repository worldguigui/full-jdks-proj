package fc.world;

import fc.world.Ids.AgentId;
import fc.world.Ids.LocationId;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 统一世界规则的第四抽象：工作单元（WorkUnit）。
 *
 * <p>生产、维修、科研、测绘与施工共用的抽象。进度使用 {@link BigDecimal}，
 * 因为技能倍率（0.75 + 0.05 × 技能）不是整数；工人按相关技能贡献小数进度。
 * 没有 {@code domain} 的工作单元按每名工人每天 1.0 推进（兼容旧数据）。</p>
 */
public final class WorkUnit {

    private final String recipeName;
    private final BigDecimal progress;
    private final int totalTicks;
    private final List<AgentId> workers;
    private final LocationId at;
    private final SkillSet.Skill domain;

    /** 兼容：不绑定地点的工作单元。 */
    public WorkUnit(String recipeName, int progress, int totalTicks, List<AgentId> workers) {
        this(recipeName, BigDecimal.valueOf(progress), totalTicks, workers, null, null);
    }

    /** 兼容：绑定地点、整数进度的工作单元。 */
    public WorkUnit(String recipeName, int progress, int totalTicks, List<AgentId> workers,
                    LocationId at) {
        this(recipeName, BigDecimal.valueOf(progress), totalTicks, workers, at, null);
    }

    public WorkUnit(String recipeName, BigDecimal progress, int totalTicks,
                    List<AgentId> workers, LocationId at, SkillSet.Skill domain) {
        if (totalTicks < 1) {
            throw new IllegalArgumentException("工作单元总天数必须 ≥ 1: " + totalTicks);
        }
        this.recipeName = recipeName;
        this.progress = progress.max(BigDecimal.ZERO);
        this.totalTicks = totalTicks;
        this.workers = List.copyOf(workers);
        this.at = at;
        this.domain = domain;
    }

    /** 工作单元所在地点（生产结算用）。 */
    public LocationId at() {
        return at;
    }

    public String recipeName() {
        return recipeName;
    }

    /** 精确进度（可能带小数）。 */
    public BigDecimal progress() {
        return progress;
    }

    /** 向下取整的进度（CLI 展示用）。 */
    public int progressUnits() {
        return progress.setScale(0, RoundingMode.DOWN).intValue();
    }

    public int totalTicks() {
        return totalTicks;
    }

    public List<AgentId> workers() {
        return workers;
    }

    /** 推进所需的技能维度；null 表示按人头推进。 */
    public SkillSet.Skill domain() {
        return domain;
    }

    /** 是否已完成。 */
    public boolean done() {
        return progress.compareTo(BigDecimal.valueOf(totalTicks)) >= 0;
    }

    /** 推进 1.0，返回新 WorkUnit。 */
    public WorkUnit advance() {
        return advance(BigDecimal.ONE);
    }

    /** 推进指定数量（截断到总天数）。 */
    public WorkUnit advance(BigDecimal amount) {
        BigDecimal next = progress.add(amount);
        BigDecimal total = BigDecimal.valueOf(totalTicks);
        if (next.compareTo(total) > 0) {
            next = total;
        }
        return new WorkUnit(recipeName, next, totalTicks, workers, at, domain);
    }

    /** 开始同类工作单元（进度 0）。 */
    public WorkUnit withWorkers(List<AgentId> nextWorkers) {
        return new WorkUnit(recipeName, progress, totalTicks, nextWorkers, at, domain);
    }
}
