package fc.science;

import fc.world.Agent;
import fc.world.Assignment;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Location;
import fc.world.ResourceKind;
import fc.world.SkillSet;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 科研服务（CLI 入口）：立项、取消与状态展示。
 *
 * <p>立项时从实验室扣除科研数据，选出科研技能最高的在岗主体（受工位上限约束）
 * 并指派为 {@link Assignment#RESEARCH}；之后由 tick 管线的工作阶段推进。
 * 取消不退还数据成本——已经把数据消耗在实验里。</p>
 */
public final class ResearchService {

    private ResearchService() {
    }

    /** 操作结果。 */
    public record Result(boolean ok, String message) {
    }

    /** 全部科技的状态表。 */
    public static String technologies(WorldState state) {
        StringBuilder sb = new StringBuilder("---- 科技树 ----\n");
        for (Tech t : TechTree.all()) {
            String status;
            if (state.hasTech(t.id())) {
                status = "已解锁";
            } else if (state.isResearching(t.id())) {
                status = "研究中";
            } else if (!TechTree.prerequisitesMet(state, t)) {
                status = "前置未满足：" + String.join(",", TechTree.missingPrerequisites(state, t));
            } else {
                status = "可立项";
            }
            sb.append("  T").append(t.tier()).append(' ')
                    .append(t.id()).append(' ').append(t.name())
                    .append("（").append(t.branch().displayName()).append("）")
                    .append(" ").append(t.days()).append(" 天｜数据 ")
                    .append(t.dataCost().stripTrailingZeros().toPlainString())
                    .append("｜").append(status).append('\n');
            sb.append("      ").append(t.effect()).append('\n');
        }
        return sb.toString();
    }

    /** 当前研究中的项目与进度。 */
    public static String status(WorldState state) {
        if (state.researchJobs().isEmpty()) {
            return "当前没有科研项目。用 research start <科技id> <实验室> 立项。";
        }
        StringBuilder sb = new StringBuilder("---- 在研项目 ----\n");
        for (Map.Entry<Ids.LocationId, String> e : state.researchJobs().entrySet()) {
            Tech t = TechTree.find(e.getValue()).orElse(null);
            double p = ResearchSystemImpl.progress(state, e.getKey());
            sb.append("  ").append(e.getValue())
                    .append(t == null ? "" : " " + t.name())
                    .append(" 于 ").append(ResearchSystemImpl.labName(state, e.getKey()))
                    .append("  进度 ")
                    .append(BigDecimal.valueOf(p * 100)
                            .setScale(1, java.math.RoundingMode.HALF_UP).toPlainString())
                    .append("%\n");
        }
        sb.append("  已解锁：");
        if (state.techs().isEmpty()) {
            sb.append("（无）");
        } else {
            sb.append(String.join(", ", state.techs()));
        }
        return sb.toString();
    }

    /** 立项。 */
    public static Result start(WorldState state, String techId, Ids.LocationId lab) {
        Tech tech;
        try {
            tech = TechTree.require(techId);
        } catch (IllegalArgumentException e) {
            return new Result(false, e.getMessage());
        }
        if (state.hasTech(tech.id())) {
            return new Result(false, "科技已解锁：" + tech.name());
        }
        if (state.isResearching(tech.id())) {
            return new Result(false, "该科技已在研究中：" + tech.name());
        }
        List<String> missing = TechTree.missingPrerequisites(state, tech);
        if (!missing.isEmpty()) {
            return new Result(false, "前置科技未解锁：" + String.join(", ", missing));
        }
        Location loc = state.graph().get(lab);
        if (loc == null) {
            return new Result(false, "实验室不存在: " + lab.id());
        }
        String kind = state.facilityKind(lab);
        if (!kind.isEmpty() && !"LAB".equalsIgnoreCase(kind)) {
            return new Result(false, loc.name() + " 不是实验室（设施种类 " + kind + "）");
        }
        if (state.researchJobAt(lab) != null) {
            return new Result(false, loc.name() + " 已有在研项目："
                    + state.researchJobAt(lab));
        }
        List<Agent> candidates = new ArrayList<>();
        for (Agent a : state.agentsAt(lab)) {
            if (a.assignment() != Assignment.TRAVELING) {
                candidates.add(a);
            }
        }
        if (candidates.isEmpty()) {
            return new Result(false, loc.name() + " 没有可参与研究的主体");
        }
        candidates.sort(Comparator
                .comparingInt((Agent a) -> a.skills().get(SkillSet.Skill.RESEARCH)).reversed()
                .thenComparingLong(a -> a.id().id()));
        BigDecimal have = state.goods(lab, ResourceKind.RESEARCH);
        if (have.compareTo(tech.dataCost()) < 0) {
            return new Result(false, "科研数据不足：需要 "
                    + tech.dataCost().stripTrailingZeros().toPlainString()
                    + "，实验室仅有 " + have.stripTrailingZeros().toPlainString());
        }

        state.consume(lab, ResourceKind.RESEARCH, tech.dataCost(), "科研立项 " + tech.id());
        int slots = state.workerCapacity(lab);
        if (slots <= 0) {
            slots = ResearchSystemImpl.DEFAULT_LAB_SLOTS;
        }
        for (int i = 0; i < Math.min(slots, candidates.size()); i++) {
            candidates.get(i).assign(Assignment.RESEARCH);
        }
        state.startResearchJob(lab, tech.id());
        state.chronicle().record(new GameEvent.ResearchStarted(
                state.now(), lab, tech.id(), tech.name(), tech.days(), tech.dataCost()));
        return new Result(true, "立项：" + tech.name() + " 于 " + loc.name()
                + "，预计 " + tech.days() + " 个工日，科研技能越高进度越快。");
    }

    /** 取消立项（不退还科研数据）。 */
    public static Result cancel(WorldState state, Ids.LocationId lab) {
        String techId = state.cancelResearchJob(lab);
        if (techId == null) {
            return new Result(false, "该地点没有在研项目: " + lab.id());
        }
        state.removeWorkUnit(ResearchSystemImpl.key(techId, lab));
        for (Agent a : state.agentsAt(lab)) {
            if (a.assignment() == Assignment.RESEARCH) {
                a.assign(Assignment.WORKING);
            }
        }
        return new Result(true, "已取消 " + ResearchSystemImpl.labName(state, lab)
                + " 的科研项目 " + techId);
    }

    /** 找到玩家可用于研究的实验室（优先配置为 LAB 的设施，按 id 排序）。 */
    public static List<Ids.LocationId> labs(WorldState state) {
        List<Ids.LocationId> out = new ArrayList<>();
        for (Map.Entry<Ids.LocationId, String> e : state.facilityKinds().entrySet()) {
            if ("LAB".equalsIgnoreCase(e.getValue())) {
                out.add(e.getKey());
            }
        }
        return out;
    }
}
