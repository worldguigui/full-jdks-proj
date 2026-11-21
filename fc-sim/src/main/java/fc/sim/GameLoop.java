package fc.sim;

import fc.sim.tick.TickPipeline;
import fc.world.WorldState;
import fc.world.time.Stardate;

/**
 * 游戏主循环（GameLoop）。
 *
 * <p>持有 {@link WorldState}，通过 {@link #next(long)} 按天执行 {@link TickPipeline}。
 * 时间推进只能经由管线，避免出现绕过各阶段的旁路。</p>
 */
public final class GameLoop {

    private final WorldState state;
    private final TickPipeline pipeline;

    public GameLoop(WorldState state, TickPipeline pipeline) {
        this.state = state;
        this.pipeline = pipeline;
    }

    public WorldState state() {
        return state;
    }

    /** 当前 Stardate。 */
    public Stardate now() {
        return state.now();
    }

    /** 上一个 tick 的报告行。 */
    public java.util.List<String> lastReport() {
        return pipeline.lastReport();
    }

    /**
     * 推进世界 N 天。
     *
     * <p>存在待决决策时立即停止推进：玩家必须先做出选择，时间才会继续。
     * 因此一次 {@code next 30} 可能只推进到产生决策的那一天。</p>
     *
     * @param days 推进天数
     * @return 推进后的 Stardate
     */
    public Stardate next(long days) {
        if (days < 0) {
            throw new IllegalArgumentException("next 天数不得为负: " + days);
        }
        for (long i = 0; i < days; i++) {
            if (state.hasPendingDecision() || state.ending() != null) {
                break;
            }
            pipeline.tick(state);
        }
        return state.now();
    }

    /** 当前是否有待决决策（有则 next 会拒绝继续推进）。 */
    public boolean awaitingDecision() {
        return state.hasPendingDecision();
    }
}
