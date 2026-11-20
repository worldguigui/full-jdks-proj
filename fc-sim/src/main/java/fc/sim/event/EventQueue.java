package fc.sim.event;

import fc.world.GameEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Tick 内事件队列（有序、确定性）。
 *
 * <p>设计依据（game-design §8.3）：Tick 内事件走**有序确定性队列**，由编年史阶段统一落库；
 * {@code Flow} 只在 Tick 结束后用于报告/遥测，不参与状态写入。</p>
 */
public final class EventQueue {

    private final List<GameEvent> pending = new ArrayList<>();

    /** 入队（按入队顺序，即阶段执行顺序）。 */
    public void enqueue(GameEvent event) {
        if (event != null) {
            pending.add(event);
        }
    }

    /** 当前待处理数量。 */
    public int size() {
        return pending.size();
    }

    /** 取出全部并按入队顺序清空。 */
    public List<GameEvent> drain() {
        List<GameEvent> out = List.copyOf(pending);
        pending.clear();
        return out;
    }

    /** 只读预览（不清空）。 */
    public List<GameEvent> peek() {
        return List.copyOf(pending);
    }
}
