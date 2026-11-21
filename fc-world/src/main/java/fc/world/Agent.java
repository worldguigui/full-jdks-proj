package fc.world;

import fc.world.Ids.AgentId;
import fc.world.Ids.LocationId;

/**
 * 统一世界规则的第三抽象：行为主体（Agent）。
 *
 * <p><strong>船员 / 居民 / 使者是同一类型的三种角色</strong>（{@link AgentRole}），
 * 不允许再定义 Crew / Resident / Envoy 子类，否则违反「统一主体」约束。</p>
 *
 * <p>决策循环由 tick 管线的感知阶段（{@code PerceiveStage}）与决策阶段
 * （{@code DecideStage}）驱动，本类只承载状态与受控修改方法。</p>
 */
public final class Agent {

    private final AgentId id;
    private final AgentRole role;
    private final Trait trait;
    private final String name;
    private LocationId location;
    private Needs needs;
    private Assignment assignment;
    private SkillSet skills;
    private int workdays;
    private boolean starvingAlerted;
    private LocationId travelDestination;
    private int travelTicksRemaining;
    private long lastAteDay = Long.MIN_VALUE;

    /** 兼容旧世界的单一技能：六维同值。 */
    public Agent(AgentId id, AgentRole role, Trait trait, String name,
                 LocationId location, Needs needs, Assignment assignment, int skill) {
        this(id, role, trait, name, location, needs, assignment, SkillSet.of(skill));
    }

    public Agent(AgentId id, AgentRole role, Trait trait, String name,
                 LocationId location, Needs needs, Assignment assignment, SkillSet skills) {
        this.id = id;
        this.role = role;
        this.trait = trait;
        this.name = name;
        this.location = location;
        this.needs = needs;
        this.assignment = assignment;
        this.skills = skills;
    }

    public AgentId id() {
        return id;
    }

    public AgentRole role() {
        return role;
    }

    public Trait trait() {
        return trait;
    }

    public String name() {
        return name;
    }

    public LocationId location() {
        return location;
    }

    public Needs needs() {
        return needs;
    }

    public Assignment assignment() {
        return assignment;
    }

    /** 六维技能组。 */
    public SkillSet skills() {
        return skills;
    }

    /** 最高技能等级 0..10（兼容旧 UI 与状态哈希）。 */
    public int skill() {
        return skills.highest();
    }

    // ---- 受控修改（仅 tick 阶段调用） ----

    /** 感知阶段：刷新需求。 */
    public void perceive(int hungerDelta, int fatigueDelta, int moraleDelta) {
        this.needs = needs.withHunger(hungerDelta)
                .withFatigue(fatigueDelta)
                .withMorale(moraleDelta);
    }

    /** 决策阶段：改变指派。 */
    public void assign(Assignment next) {
        this.assignment = next;
    }

    /** 进食：显著降低饥饿。 */
    public void eat(int relief) {
        this.needs = needs.withHunger(-relief);
    }

    /** 休息：降低疲劳。 */
    public void rest(int relief) {
        this.needs = needs.withFatigue(-relief);
    }

    /** 技能成长（按具体维度）。 */
    public void gainSkill(SkillSet.Skill skill, int delta) {
        this.skills = skills.increase(skill, delta);
    }

    /** 兼容：默认提升工程技能（生产劳动）。 */
    public void gainSkill(int delta) {
        gainSkill(SkillSet.Skill.ENGINEERING, delta);
    }

    /**
     * 记录一个相关工作日；达到 30 的倍数时返回 true，供决策阶段做技能成长判定。
     * 计数器进入状态哈希，保证同种子重放一致。
     */
    public boolean recordWorkday() {
        workdays++;
        return workdays % 30 == 0;
    }

    public int workdays() {
        return workdays;
    }

    /** 是否已就"饥饿告急"告警过（避免日志刷屏）。 */
    public boolean starvingAlerted() {
        return starvingAlerted;
    }

    public void setStarvingAlerted(boolean flagged) {
        this.starvingAlerted = flagged;
    }

    /** 迁移到另一地点（即时移动；供抵达结算使用）。 */
    public void moveTo(LocationId target) {
        this.location = target;
    }

    // ---- 迁徙（沿航线，需要行程时间） ----

    /** 开始沿航线迁徙：记录目的地与剩余天数。 */
    public void beginTravel(LocationId destination, int ticks) {
        this.travelDestination = destination;
        this.travelTicksRemaining = Math.max(1, ticks);
        this.assignment = Assignment.TRAVELING;
    }

    public boolean isTraveling() {
        return travelDestination != null;
    }

    public LocationId travelDestination() {
        return travelDestination;
    }

    public int travelTicksRemaining() {
        return travelTicksRemaining;
    }

    /** 推进一天行程；抵达时更新所在地点并返回 true。 */
    public boolean advanceTravel() {
        if (travelDestination == null) {
            return false;
        }
        travelTicksRemaining--;
        if (travelTicksRemaining > 0) {
            return false;
        }
        this.location = travelDestination;
        this.travelDestination = null;
        this.travelTicksRemaining = 0;
        this.assignment = Assignment.IDLE;
        return true;
    }

    // ---- 进食记录（避免同一天重复进食） ----

    /** 记录该主体在指定 Stardate 已进食。 */
    public void recordAte(long day) {
        this.lastAteDay = day;
    }

    /** 该主体在指定 Stardate 是否已经吃过。 */
    public boolean ateOn(long day) {
        return lastAteDay == day;
    }

    /** 最后一次进食的日期（状态哈希用）。 */
    public long lastAteDay() {
        return lastAteDay;
    }

    /** 行为主体角色：船员 / 居民 / 使者。 */
    public enum AgentRole {
        CREW, RESIDENT, ENVOY
    }
}
