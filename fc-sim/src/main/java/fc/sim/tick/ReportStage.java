package fc.sim.tick;

import fc.world.Agent;
import fc.world.WorldState;

/**
 * 阶段 12：报告聚合（按严重度给出本回合可读摘要）。
 *
 * <p>只读阶段：把时间、环境、主体状态、运输与待决事项聚合成一行摘要，供 CLI 展示；
 * 不写任何世界状态，因此不影响确定性。</p>
 */
public final class ReportStage implements TickStage {

    private final fc.sim.spi.GoalSystem goals;

    public ReportStage() {
        this(null);
    }

    public ReportStage(fc.sim.spi.GoalSystem goals) {
        this.goals = goals;
    }

    @Override
    public String name() {
        return "12-report";
    }

    @Override
    public void apply(WorldState state, TickContext ctx) {
        // 目标判定在汇总前执行：新达成的目标会出现在本回合报告里。
        if (goals != null) {
            for (String goal : goals.evaluate(state, ctx)) {
                ctx.report("阶段目标达成：" + goal);
            }
        }
        int working = 0;
        int resting = 0;
        int starving = 0;
        for (Agent a : state.agents().values()) {
            if (a.assignment() == fc.world.Assignment.WORKING) {
                working++;
            } else if (a.assignment() == fc.world.Assignment.RESTING) {
                resting++;
            }
            if (a.needs().starving()) {
                starving++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("环境强度 ").append(ctx.weather())
                .append("｜在岗 ").append(working).append(" 休息 ").append(resting);
        if (starving > 0) {
            sb.append(" 饥荒告急 ").append(starving);
        }
        int transfers = state.activeTransfers().size();
        if (transfers > 0) {
            sb.append("｜在途 ").append(transfers);
        }
        if (state.hasPendingDecision()) {
            sb.append("｜待决 ").append(state.pendingDecisions().size());
        }
        ctx.report(sb.toString());
    }
}
