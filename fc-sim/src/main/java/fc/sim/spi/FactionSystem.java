package fc.sim.spi;

import fc.sim.tick.TickContext;
import fc.world.WorldState;

/**
 * 势力系统 SPI（阶段 3 填充点）。
 *
 * <p>势力按配置的优先货物、库存目标与财政储备做出采购/储备/外交姿态调整。
 * 方向约束与市场系统相同：{@code fc-sim} 不依赖 {@code fc-diplomacy}，
 * 因此这里只定义接口，实现由运行时注入。</p>
 */
public interface FactionSystem {

    /** 每个 tick 执行（在阶段 3 调用）。 */
    void onTick(WorldState state, TickContext ctx);
}
