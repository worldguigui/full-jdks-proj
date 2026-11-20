package fc.sim.tick;

import fc.sim.rng.RngStreams;
import fc.world.GameEvent;
import fc.world.Location;
import fc.world.WorldState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 阶段 10：事件引擎（条件触发 + 加权抽样）。
 *
 * <p>M2 实现：环境强度越高，离子风暴概率越大；地点在星系与异常点中按 id 有序抽取
 * （有序集合保证确定性）。连锁因果与"待决选项"在 M4 随外交/任务接入。</p>
 */
public final class WorldEventStage implements TickStage {

    @Override
    public String name() {
        return "10-event";
    }

    @Override
    public void apply(WorldState state, TickContext ctx) {
        var rng = ctx.rng().stream(RngStreams.EVENTS);

        List<Location> candidates = new ArrayList<>();
        for (Location l : state.graph().nodes().values()) {
            if (l instanceof Location.StarSystem || l instanceof Location.Anomaly) {
                candidates.add(l);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        candidates.sort(Comparator.comparingLong(l -> l.id().id()));

        double probability = 0.02 + (ctx.weather() / 100.0) * 0.05;
        if (!RngStreams.chance(rng, probability)) {
            return;
        }
        Location target = candidates.get(rng.nextInt(candidates.size()));
        int severity = 1 + rng.nextInt(5);
        ctx.events().enqueue(new GameEvent.IonStorm(state.now(), target.id(), severity));
    }
}
