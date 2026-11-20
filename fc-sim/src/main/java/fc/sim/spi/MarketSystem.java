package fc.sim.spi;

import fc.sim.tick.TickContext;
import fc.world.WorldState;

/**
 * 市场系统 SPI（阶段 9 填充点）。
 *
 * <p>方向约束：{@code fc-sim} 不依赖 {@code fc-economy}（economy 依赖 sim），
 * 因此这里只定义接口，实现由运行时注入。M2 时该位置为空实现，M3 起由
 * {@code fc.economy.MarketSystemImpl} 接管（工资结算、市场状态维护）。</p>
 */
public interface MarketSystem {

    /** 每个 tick 执行（在阶段 9 调用）。 */
    void onTick(WorldState state, TickContext ctx);
}
