package fc.science;

import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 固定科技树（三阶八项，与 M4 规格一致）。
 *
 * <pre>
 * Tier 1：测绘无人机、水培农业、货舱框架
 * Tier 2：高效曲率线圈、自动化工坊、贸易协议标准（任一 Tier 1）
 * Tier 3：星门理论、信标理论（任一 2 项 Tier 2）
 * </pre>
 *
 * <p>前置未解锁的科技不能立项；同一科技同时只能在一个实验室研究。</p>
 */
public final class TechTree {

    /** 科技 id 常量（其他系统通过 hasTech 读取效果）。 */
    public static final String SURVEY_DRONE = "SURVEY_DRONE";
    public static final String HYDROPONICS = "HYDROPONICS";
    public static final String CARGO_FRAME = "CARGO_FRAME";
    public static final String WARP_COIL = "WARP_COIL";
    public static final String AUTOMATION = "AUTOMATION";
    public static final String TRADE_PROTOCOL = "TRADE_PROTOCOL";
    public static final String GATE_THEORY = "GATE_THEORY";
    public static final String BEACON_THEORY = "BEACON_THEORY";

    private static final Map<String, Tech> TECHS = new LinkedHashMap<>();

    static {
        add(new Tech(SURVEY_DRONE, "测绘无人机", 1, 3, new BigDecimal("2"),
                List.of(), 0, "勘察工期 -25%", Tech.Branch.EXPLORATION));
        add(new Tech(HYDROPONICS, "水培农业", 1, 3, new BigDecimal("2"),
                List.of(), 0, "农场配方产出 +25%", Tech.Branch.PRODUCTION));
        add(new Tech(CARGO_FRAME, "货舱框架", 1, 3, new BigDecimal("2"),
                List.of(), 0, "舰船货舱 +50", Tech.Branch.PRODUCTION));
        add(new Tech(WARP_COIL, "高效曲率线圈", 2, 5, new BigDecimal("4"),
                List.of(), 1, "航线天数 -20%（最低 1 天）", Tech.Branch.EXPLORATION));
        add(new Tech(AUTOMATION, "自动化工坊", 2, 5, new BigDecimal("4"),
                List.of(), 1, "生产工作速度 +15%", Tech.Branch.PRODUCTION));
        add(new Tech(TRADE_PROTOCOL, "贸易协议标准", 2, 5, new BigDecimal("4"),
                List.of(), 1, "势力税率 -50%", Tech.Branch.SOCIETY));
        add(new Tech(GATE_THEORY, "星门理论", 3, 8, new BigDecimal("6"),
                List.of(), 2, "解锁终局工程：星门", Tech.Branch.ENDGAME));
        add(new Tech(BEACON_THEORY, "信标理论", 3, 8, new BigDecimal("6"),
                List.of(), 2, "解锁终局工程：边境信标", Tech.Branch.ENDGAME));
    }

    private TechTree() {
    }

    private static void add(Tech tech) {
        TECHS.put(tech.id(), tech);
    }

    public static List<Tech> all() {
        return List.copyOf(TECHS.values());
    }

    public static Optional<Tech> find(String id) {
        return Optional.ofNullable(TECHS.get(id));
    }

    public static Tech require(String id) {
        Tech t = TECHS.get(id);
        if (t == null) {
            throw new IllegalArgumentException("未知科技: " + id + "（可选 " + TECHS.keySet() + "）");
        }
        return t;
    }

    /** 某阶已解锁的科技数量。 */
    public static int completedInTier(WorldState state, int tier) {
        int n = 0;
        for (Tech t : TECHS.values()) {
            if (t.tier() == tier && state.hasTech(t.id())) {
                n++;
            }
        }
        return n;
    }

    /** 前置条件是否全部满足（AND 前置 + 任一前置阶）。 */
    public static boolean prerequisitesMet(WorldState state, Tech tech) {
        for (String p : tech.prerequisites()) {
            if (!state.hasTech(p)) {
                return false;
            }
        }
        return tech.anyOfTier() == 0 || completedInTier(state, tech.anyOfTier()) >= 1;
    }

    /** 可立项的科技：未解锁、前置满足、且当前没有正在研究（包含自身）。 */
    public static List<Tech> available(WorldState state) {
        List<Tech> out = new ArrayList<>();
        for (Tech t : TECHS.values()) {
            if (!state.hasTech(t.id()) && prerequisitesMet(state, t)
                    && !state.isResearching(t.id())) {
                out.add(t);
            }
        }
        return out;
    }

    /** 前置未满足的原因（用于展示）。 */
    public static List<String> missingPrerequisites(WorldState state, Tech tech) {
        List<String> out = new ArrayList<>();
        for (String p : tech.prerequisites()) {
            if (!state.hasTech(p)) {
                out.add(p);
            }
        }
        if (tech.anyOfTier() > 0 && completedInTier(state, tech.anyOfTier()) < 1) {
            out.add("任一 Tier " + tech.anyOfTier() + " 科技");
        }
        return out;
    }
}
