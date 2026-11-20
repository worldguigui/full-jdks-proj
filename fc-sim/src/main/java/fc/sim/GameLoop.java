package fc.sim;

import fc.sim.tick.TickPipeline;
import fc.world.WorldState;
import fc.world.time.Stardate;

/**
 * 游戏主循环（GameLoop）。
 *
 * <p>持有 {@link WorldState}，通过 {@link #next(long)} 按天推进。
 * 有 {@link TickPipeline} 时逐日执行管线（M1 起为时间/移动/库存三阶段）；
 * 无管线时退化为 M0 的「只推进时间」空转。</p>
 */
public final class GameLoop {

    private final WorldState state;
    private final TickPipeline pipeline;

    /** M0 兼容构造：无管线，next 只推进时间。 */
    public GameLoop(WorldState state) {
        this(state, null);
    }

    /** M1 起：带 Tick 管线。 */
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

    /** 上一个 tick 的报告行（无管线时为空）。 */
    public java.util.List<String> lastReport() {
        return pipeline == null ? java.util.List.of() : pipeline.lastReport();
    }

    /** 是否有 Tick 管线。 */
    public boolean hasPipeline() {
        return pipeline != null;
    }

    /**
     * 推进世界 N 天：逐日执行管线（无管线时只推进时间）。
     *
     * @param days 推进天数
     * @return 推进后的 Stardate
     */
    public Stardate next(long days) {
        if (days < 0) {
            throw new IllegalArgumentException("next 天数不得为负: " + days);
        }
        for (long i = 0; i < days; i++) {
            if (pipeline != null) {
                pipeline.tick(state);
            } else {
                state.advanceTo(state.now().plusDays(1));
            }
        }
        return state.now();
    }
}
