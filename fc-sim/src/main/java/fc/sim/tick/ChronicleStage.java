package fc.sim.tick;

import fc.world.GameEvent;
import fc.world.WorldState;

import java.util.List;

/**
 * 阶段 11：编年史落库（把本 tick 的有序事件写入编年史）。
 *
 * <p>这是**状态写入**：只有本阶段把事件写进 {@code Chronicle}。
 * 之后阶段 12 与 tick 后的 Flow 广播都只读，不改变历史。</p>
 */
public final class ChronicleStage implements TickStage {

    @Override
    public String name() {
        return "11-chronicle";
    }

    @Override
    public void apply(WorldState state, TickContext ctx) {
        List<GameEvent> events = ctx.events().drain();
        ctx.setDrained(events);
        for (GameEvent e : events) {
            state.chronicle().record(e);
        }
        ctx.report("本轮事件 " + events.size() + " 条");
    }
}
