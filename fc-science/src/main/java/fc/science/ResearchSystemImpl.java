package fc.science;

import fc.sim.spi.ProductionSystem;
import fc.sim.tick.TickContext;
import fc.world.Agent;
import fc.world.Assignment;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Location;
import fc.world.SkillSet;
import fc.world.WorkUnit;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 科研系统：把实验室的科研立项变成工作单元，并在完工时解锁科技。
 *
 * <p>这是与生产系统并列的 {@link ProductionSystem}，由工作阶段统一推进；
 * 科研单元的进度按研究技能倍率增长。完工结算只解锁一次（工作单元随即移除），
 * 并记录 {@link GameEvent.TechUnlocked} 因果事件。</p>
 */
public final class ResearchSystemImpl implements ProductionSystem {

    /** 默认实验室工位（未配置时）。 */
    public static final int DEFAULT_LAB_SLOTS = 4;

    @Override
    public void startUnits(WorldState state, TickContext ctx) {
        for (Map.Entry<Ids.LocationId, String> e : state.researchJobs().entrySet()) {
            Ids.LocationId lab = e.getKey();
            String techId = e.getValue();
            String key = key(techId, lab);
            if (state.workUnits().containsKey(key)) {
                continue;
            }
            if (state.graph().get(lab) == null || state.hasTech(techId)) {
                state.cancelResearchJob(lab);
                continue;
            }
            List<Ids.AgentId> workers = selectedWorkers(state, lab);
            if (workers.isEmpty()) {
                continue;
            }
            Tech tech = TechTree.find(techId).orElse(null);
            if (tech == null) {
                state.cancelResearchJob(lab);
                continue;
            }
            state.putWorkUnit(key, new WorkUnit(techId, BigDecimal.ZERO, tech.days(),
                    workers, lab, SkillSet.Skill.RESEARCH));
        }
    }

    @Override
    public void onComplete(WorldState state, TickContext ctx, String key, WorkUnit unit) {
        String techId = unit.recipeName();
        Tech tech = TechTree.find(techId).orElse(null);
        if (tech == null || unit.at() == null) {
            return;
        }
        Ids.LocationId lab = unit.at();
        if (!techId.equals(state.researchJobAt(lab))) {
            return;
        }
        state.unlockTech(techId);
        if (TechTree.CARGO_FRAME.equals(techId)) {
            // 货舱框架：已服役舰船立即扩容；之后建造的舰船在交付时应用。
            for (Location.Ship ship : state.graph().ships()) {
                ship.expandCargo(new BigDecimal("50"));
            }
        }
        state.cancelResearchJob(lab);
        for (Ids.AgentId id : unit.workers()) {
            Agent a = state.agents().get(id);
            if (a != null && a.assignment() == Assignment.RESEARCH) {
                a.assign(Assignment.WORKING);
            }
        }
        ctx.events().enqueue(new GameEvent.TechUnlocked(
                state.now(), lab, tech.id(), tech.name(), tech.effect()));
        ctx.report("科技解锁：" + tech.name() + "（" + tech.effect() + "）");
    }

    /** 实验室里被指派为科研的主体（按科研技能降序，受工位上限约束）。 */
    private static List<Ids.AgentId> selectedWorkers(WorldState state, Ids.LocationId lab) {
        List<Agent> candidates = new ArrayList<>();
        for (Agent a : state.agentsAt(lab)) {
            if (a.assignment() == Assignment.RESEARCH) {
                candidates.add(a);
            }
        }
        candidates.sort(Comparator
                .comparingInt((Agent a) -> a.skills().get(SkillSet.Skill.RESEARCH)).reversed()
                .thenComparingLong(a -> a.id().id()));
        int slots = state.workerCapacity(lab);
        if (slots <= 0) {
            slots = DEFAULT_LAB_SLOTS;
        }
        List<Ids.AgentId> out = new ArrayList<>();
        for (int i = 0; i < Math.min(slots, candidates.size()); i++) {
            out.add(candidates.get(i).id());
        }
        return out;
    }

    /** 科研单元 key（与生产配方 key 隔离）。 */
    public static String key(String techId, Ids.LocationId lab) {
        return "research:" + techId + "@" + lab.id();
    }

    /** 某实验室的当前研究进度（0..1，无立项为 0）。 */
    public static double progress(WorldState state, Ids.LocationId lab) {
        String techId = state.researchJobAt(lab);
        if (techId == null) {
            return 0;
        }
        WorkUnit unit = state.workUnits().get(key(techId, lab));
        if (unit == null) {
            return 0;
        }
        return unit.progress().doubleValue() / unit.totalTicks();
    }

    /** 便于诊断：实验室名称。 */
    public static String labName(WorldState state, Ids.LocationId lab) {
        Location loc = state.graph().get(lab);
        return loc == null ? ("#" + lab.id()) : loc.name();
    }
}
