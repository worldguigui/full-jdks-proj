package fc.sim.spi;

import fc.sim.tick.TickContext;
import fc.world.WorldState;

/**
 * 殖民地系统 SPI（阶段 2 填充点）。
 *
 * <p>负责殖民地人口增长、生活物资维护与产出加成。升级本身由玩家通过
 * {@code upgrade} 命令发起（{@code fc-colony.ColonyService}），不在本接口内自动完成。</p>
 */
public interface ColonySystem {

    /** 每个 tick 执行（在阶段 2 自然产出之后调用）。 */
    void onTick(WorldState state, TickContext ctx);
}
