package fc.sim.spi;

import fc.sim.tick.TickContext;
import fc.world.WorldState;
import fc.world.WorkUnit;

/**
 * 生产系统 SPI（阶段 6 填充点）。
 *
 * <p>分工：{@code fc-sim} 的 {@code WorkStage} 负责推进工作单元进度（并发 + 固定顺序汇合）；
 * 本接口负责**开工条件**（按配方扣输入创建单元）与**完工结算**（发放产出）。
 * 实现由 {@code fc.economy.ProductionSystemImpl} 在运行时注入。</p>
 */
public interface ProductionSystem {

    /** 阶段 6 开始前：为可开工的配方创建 WorkUnit（扣除输入）。 */
    void startUnits(WorldState state, TickContext ctx);

    /** 某工作单元完成：结算产出。 */
    void onComplete(WorldState state, TickContext ctx, String key, WorkUnit unit);
}
