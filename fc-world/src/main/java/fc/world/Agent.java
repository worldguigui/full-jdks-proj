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
    private int skill;
    private boolean starvingAlerted;

    public Agent(AgentId id, AgentRole role, Trait trait, String name,
                 LocationId location, Needs needs, Assignment assignment, int skill) {
        this.id = id;
        this.role = role;
        this.trait = trait;
        this.name = name;
        this.location = location;
        this.needs = needs;
        this.assignment = assignment;
        this.skill = skill;
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

    /** 技能等级 0..10。 */
    public int skill() {
        return skill;
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

    /** 技能成长（长期工作）。 */
    public void gainSkill(int delta) {
        this.skill = Math.min(10, skill + delta);
    }

    /** 是否已就"饥饿告急"告警过（避免日志刷屏）。 */
    public boolean starvingAlerted() {
        return starvingAlerted;
    }

    public void setStarvingAlerted(boolean flagged) {
        this.starvingAlerted = flagged;
    }

    /** 迁移到另一地点。 */
    public void moveTo(LocationId target) {
        this.location = target;
    }

    /** 行为主体角色：船员 / 居民 / 使者。 */
    public enum AgentRole {
        CREW, RESIDENT, ENVOY
    }
}
