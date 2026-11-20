package fc.sim.tick;

import fc.sim.rng.RngStreams;
import fc.world.WorldState;

/**
 * 阶段 1：时间推进。
 *
 * <p>Stardate +1，并采样当日环境强度（后续阶段据此调制产出与士气）。</p>
 */
public final class TimeStage implements TickStage {

    @Override
    public String name() {
        return "1-time";
    }

    @Override
    public void apply(WorldState state, TickContext ctx) {
        state.advanceTo(state.now().plusDays(1));
        ctx.setWeather(ctx.rng().stream(RngStreams.WEATHER).nextInt(101));
    }
}
