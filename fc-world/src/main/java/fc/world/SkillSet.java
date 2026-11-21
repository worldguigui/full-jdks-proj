package fc.world;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 六维技能组（SkillSet）：驾驶、工程、科研、医疗、贸易、探索。
 *
 * <p>技能等级 0..10。工作单元的推进倍率为 {@code 0.75 + 0.05 × 相关技能}，
 * 因此 5 级技能恰好等于 1.0 标准人日；科研项目、测绘、航行风险与贸易价差
 * 分别读取不同维度的技能。</p>
 */
public record SkillSet(int pilot, int engineering, int research,
                       int medical, int trade, int exploration) {

    /** 六种技能。 */
    public enum Skill {
        PILOT("驾驶"),
        ENGINEERING("工程"),
        RESEARCH("科研"),
        MEDICAL("医疗"),
        TRADE("贸易"),
        EXPLORATION("探索");

        private final String display;

        Skill(String display) {
            this.display = display;
        }

        public String displayName() {
            return display;
        }
    }

    public static final SkillSet ZERO = new SkillSet(0, 0, 0, 0, 0, 0);

    public SkillSet {
        pilot = clamp(pilot);
        engineering = clamp(engineering);
        research = clamp(research);
        medical = clamp(medical);
        trade = clamp(trade);
        exploration = clamp(exploration);
    }

    /** 兼容旧世界的单一技能：六维同值。 */
    public static SkillSet of(int general) {
        int v = clamp(general);
        return new SkillSet(v, v, v, v, v, v);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(10, v));
    }

    public int get(Skill skill) {
        return switch (skill) {
            case PILOT -> pilot;
            case ENGINEERING -> engineering;
            case RESEARCH -> research;
            case MEDICAL -> medical;
            case TRADE -> trade;
            case EXPLORATION -> exploration;
        };
    }

    /** 返回把某项技能设为给定值的副本（0..10 自动夹取）。 */
    public SkillSet with(Skill skill, int value) {
        int v = clamp(value);
        return switch (skill) {
            case PILOT -> new SkillSet(v, engineering, research, medical, trade, exploration);
            case ENGINEERING -> new SkillSet(pilot, v, research, medical, trade, exploration);
            case RESEARCH -> new SkillSet(pilot, engineering, v, medical, trade, exploration);
            case MEDICAL -> new SkillSet(pilot, engineering, research, v, trade, exploration);
            case TRADE -> new SkillSet(pilot, engineering, research, medical, v, exploration);
            case EXPLORATION -> new SkillSet(pilot, engineering, research, medical, trade, v);
        };
    }

    /** 增加某项技能（不超过 10）。 */
    public SkillSet increase(Skill skill, int delta) {
        return with(skill, get(skill) + delta);
    }

    /** 最高技能等级（兼容旧 UI/哈希）。 */
    public int highest() {
        int max = 0;
        for (Skill s : Skill.values()) {
            max = Math.max(max, get(s));
        }
        return max;
    }

    /**
     * 某技能的工作倍率：{@code 0.75 + 0.05 × skill}，保留两位小数。
     * 技能 5 ⇒ 1.00；技能 0 ⇒ 0.75；技能 10 ⇒ 1.25。
     */
    public BigDecimal workFactor(Skill skill) {
        return new BigDecimal("0.75")
                .add(new BigDecimal("0.05").multiply(BigDecimal.valueOf(get(skill))))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** 展示串，例如 {@code 驾5 工4 研3 医2 贸6 探7}。 */
    public String display() {
        return "驾" + pilot + " 工" + engineering + " 研" + research
                + " 医" + medical + " 贸" + trade + " 探" + exploration;
    }
}
