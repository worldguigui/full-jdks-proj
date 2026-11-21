package fc.sim.tick;

import fc.sim.rng.RngStreams;
import fc.world.Agent;
import fc.world.Assignment;
import fc.world.GameEvent;
import fc.world.SkillSet;
import fc.world.WorldState;

/**
 * 阶段 5：主体决策（统一决策循环）。
 *
 * <p>优先级：生存（饥饿）＞ 休息（疲劳）＞ 归属/尊严（士气，可能拒绝指令）＞ 工作。
 * 决策只改变自身的 {@link Assignment}，以及通过进食影响库存与账本。</p>
 */
public final class DecideStage implements TickStage {

    @Override
    public String name() {
        return "5-decide";
    }

    @Override
    public void apply(WorldState state, TickContext ctx) {
        var rng = ctx.rng().stream(RngStreams.AGENTS);
        for (Agent a : Agents.sorted(state)) {
            // 迁徙中的主体不接受新决策，由感知阶段推进行程
            if (a.isTraveling()) {
                continue;
            }
            // 1) 生存：饥饿危急 → 就地进食，否则沿航线前往最近有粮地点
            if (a.needs().starving()) {
                int before = a.needs().hunger();
                if (!a.ateOn(state.now().day()) && Eating.tryEat(state, a, 45)) {
                    ctx.events().enqueue(new GameEvent.CrewAte(
                            state.now(), a.location(), a.id(), before));
                    a.setStarvingAlerted(false);
                    a.assign(Assignment.IDLE);
                    continue;
                }
                var food = Foraging.nearestFood(state, a.location(), 6);
                if (food.isPresent()) {
                    var from = a.location();
                    a.beginTravel(food.get().id(), food.get().ticks());
                    ctx.events().enqueue(new GameEvent.CrewMigrated(
                            state.now(), from, a.id(), food.get().id(),
                            "觅食，行程 " + food.get().ticks() + " 天"));
                    continue;
                }
                if (!a.starvingAlerted()) {
                    ctx.events().enqueue(new GameEvent.CrewStarving(
                            state.now(), a.location(), a.id(), a.needs().hunger()));
                    a.setStarvingAlerted(true);
                }
                a.assign(Assignment.IDLE);
                continue;
            }
            if (a.needs().hunger() < 60) {
                a.setStarvingAlerted(false);
            }

            // 2) 疲劳：需要休息
            if (a.needs().exhausted()) {
                a.assign(Assignment.RESTING);
                continue;
            }

            // 3) 士气低落：可能拒绝指令（性格影响）
            if (a.needs().demoralized()) {
                double refuse = switch (a.trait()) {
                    case ANXIOUS -> 0.45;
                    case LAZY -> 0.35;
                    case STOIC -> 0.15;
                    case DILIGENT -> 0.10;
                };
                if (RngStreams.chance(rng, refuse)) {
                    ctx.events().enqueue(new GameEvent.CrewRefusedDuty(
                            state.now(), a.location(), a.id(), "士气低落（" + a.needs().morale() + "）"));
                    a.assign(Assignment.IDLE);
                    continue;
                }
            }

            // 4) 专业指派（科研/测绘/维修）由指令设置，决策阶段保留；
            //    其余主体默认回到工作。
            Assignment sticky = a.assignment();
            boolean professional = sticky == Assignment.RESEARCH
                    || sticky == Assignment.SURVEYING || sticky == Assignment.REPAIRING;
            if (!professional) {
                a.assign(Assignment.WORKING);
                sticky = Assignment.WORKING;
            }

            // 技能随长期工作缓慢成长：每 30 个工作日结算一次，10% 概率提升相关维度。
            if (a.recordWorkday() && RngStreams.chance(rng, 0.10)) {
                switch (sticky) {
                    case RESEARCH -> a.gainSkill(SkillSet.Skill.RESEARCH, 1);
                    case SURVEYING -> a.gainSkill(SkillSet.Skill.EXPLORATION, 1);
                    case REPAIRING -> a.gainSkill(SkillSet.Skill.ENGINEERING, 1);
                    default -> a.gainSkill(1);
                }
            }
        }
    }
}
