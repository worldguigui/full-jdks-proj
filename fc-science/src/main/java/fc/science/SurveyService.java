package fc.science;

import fc.world.Agent;
import fc.world.Assignment;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.KnowledgeState;
import fc.world.Location;
import fc.world.SurveyTask;
import fc.world.WorldState;

import java.util.Map;

/**
 * 测绘服务（CLI 入口）：在已知地点启动/取消星图测绘。
 *
 * <p>测绘要求舰船停泊在目标地点，并把船上主体指派为
 * {@link Assignment#SURVEYING}；之后由航行阶段按探索技能倍率推进。
 * 完成后地点变为 CHARTED，并产出科研数据。</p>
 */
public final class SurveyService {

    private SurveyService() {
    }

    public record Result(boolean ok, String message) {
    }

    /**
     * 测绘工期：{@code max(2, 6 - floor(EXPLORATION / 2))} 天；
     * 已解锁测绘无人机时工期 -25%（向上取整，至少 1 天）。
     */
    static int surveyDays(WorldState state, Location.Ship ship) {
        int skill = 0;
        for (Agent a : state.agentsAt(ship.id())) {
            skill = Math.max(skill, a.skills().get(fc.world.SkillSet.Skill.EXPLORATION));
        }
        int days = Math.max(2, 6 - skill / 2);
        if (state.hasTech(TechTree.SURVEY_DRONE)) {
            days = Math.max(1, (int) Math.ceil(days * 0.75));
        }
        return days;
    }

    /**
     * 近距扫描：把停泊点相邻的未知地点提升为 DETECTED（显示未知信号与航线端点）。
     * 只揭示相邻信号，不揭示库存与名称；抵达后由航行阶段转为 SURVEYED。
     */
    public static Result scan(WorldState state, Location.Ship ship) {
        if (ship.inVoyage()) {
            return new Result(false, "舰船在航中，无法扫描");
        }
        if (!ship.operational()) {
            return new Result(false, "舰船耐久为 0，需先维修");
        }
        Ids.LocationId dock = ship.parent();
        int revealed = 0;
        for (fc.world.Link link : state.graph().linksFrom(dock)) {
            Ids.LocationId other = link.from().equals(dock) ? link.to() : link.from();
            if (state.knowledgeOf(other) == KnowledgeState.UNKNOWN) {
                state.setKnowledge(other, KnowledgeState.DETECTED);
                state.chronicle().record(new GameEvent.KnowledgeGained(
                        state.now(), other, KnowledgeState.DETECTED,
                        "舰船#" + ship.shipId().id() + " 近距扫描"));
                revealed++;
            }
        }
        if (revealed == 0) {
            return new Result(true, "扫描完成：相邻星域没有新的未知信号");
        }
        return new Result(true, "扫描完成：新增 " + revealed + " 个未知信号"
                + "（可 blind 航行抵达后转为已勘察）");
    }

    public static Result start(WorldState state, Location.Ship ship, Ids.LocationId target) {
        if (ship.inVoyage()) {
            return new Result(false, "舰船在航中，无法测绘");
        }
        if (!ship.operational()) {
            return new Result(false, "舰船耐久为 0，需先维修");
        }
        Location loc = state.graph().get(target);
        if (loc == null) {
            return new Result(false, "目标地点不存在: " + target.id());
        }
        if (!ship.parent().equals(target)) {
            return new Result(false, "测绘要求舰船停泊在目标地点（当前在 "
                    + ship.parent().id() + "）");
        }
        KnowledgeState knowledge = state.knowledgeOf(target);
        if (knowledge == KnowledgeState.UNKNOWN) {
            return new Result(false, "目标尚未探测，无法测绘");
        }
        if (knowledge == KnowledgeState.CHARTED) {
            return new Result(false, "目标已完成测绘");
        }
        if (state.surveyAt(target) != null) {
            return new Result(false, "该地点已在测绘中");
        }
        boolean crew = false;
        for (Agent a : state.agentsAt(ship.id())) {
            a.assign(Assignment.SURVEYING);
            crew = true;
        }
        if (!crew) {
            return new Result(false, "舰上没有船员，无法测绘");
        }
        int days = surveyDays(state, ship);
        state.startSurvey(target, ship.shipId(), days);
        state.chronicle().record(new GameEvent.SurveyStarted(
                state.now(), target, ship.shipId(), days));
        return new Result(true, "开始测绘 " + loc.name() + "，预计 " + days + " 天（舰船停泊期间推进）。");
    }

    public static Result cancel(WorldState state, Location.Ship ship) {
        Ids.LocationId target = null;
        for (Map.Entry<Ids.LocationId, SurveyTask> e : state.surveys().entrySet()) {
            if (e.getValue().ship().equals(ship.shipId())) {
                target = e.getKey();
                break;
            }
        }
        if (target == null) {
            return new Result(false, "该舰船没有进行中的测绘任务");
        }
        state.cancelSurvey(target);
        for (Agent a : state.agentsAt(ship.id())) {
            if (a.assignment() == Assignment.SURVEYING) {
                a.assign(Assignment.WORKING);
            }
        }
        return new Result(true, "已取消对 " + target.id() + " 的测绘");
    }

    public static String list(WorldState state) {
        if (state.surveys().isEmpty()) {
            return "当前没有测绘任务。用 survey start <舰船> <目标地点> 开始。";
        }
        StringBuilder sb = new StringBuilder("---- 测绘任务 ----\n");
        for (Map.Entry<Ids.LocationId, SurveyTask> e : state.surveys().entrySet()) {
            SurveyTask t = e.getValue();
            sb.append("  ").append(e.getKey().id())
                    .append(" 舰船#").append(t.ship().id())
                    .append("  进度 ").append(t.progress().stripTrailingZeros().toPlainString())
                    .append('/').append(t.totalDays()).append('\n');
        }
        return sb.toString();
    }
}
