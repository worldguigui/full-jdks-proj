package fc.sim.tick;

import fc.sim.WorldAssembler;
import fc.sim.event.EventBus;
import fc.sim.rng.RngStreams;
import fc.world.Ids;
import fc.world.WorldState;

import java.util.List;
import java.util.Map;

/**
 * Tick 管线（M2：固定十二阶段，按依赖 DAG 排序）。
 *
 * <table>
 *   <caption>阶段顺序</caption>
 *   <tr><td>1</td><td>时间推进</td><td>2</td><td>环境与生态</td><td>3</td><td>势力 AI（M4 接管）</td></tr>
 *   <tr><td>4</td><td>主体感知</td><td>5</td><td>主体决策</td><td>6</td><td>生产与工作（虚拟线程）</td></tr>
 *   <tr><td>7</td><td>航行与物流</td><td>8</td><td>消耗与维护</td><td>9</td><td>市场与财务（M3 接管）</td></tr>
 *   <tr><td>10</td><td>事件引擎</td><td>11</td><td>编年史落库</td><td>12</td><td>报告聚合</td></tr>
 * </table>
 *
 * <p>确定性：阶段顺序固定；随机来自分系统流；tick 内事件按入队顺序落库；
 * {@link EventBus} 只在 tick 结束后广播，不参与状态写入。</p>
 */
public final class TickPipeline {

    private final List<TickStage> stages;
    private final Map<Ids.LocationId, WorldAssembler.Production> production;
    private final RngStreams rng;
    private final EventBus bus;
    private TickContext lastCtx;

    public TickPipeline(List<TickStage> stages,
                        Map<Ids.LocationId, WorldAssembler.Production> production,
                        RngStreams rng, EventBus bus) {
        this.stages = List.copyOf(stages);
        this.production = Map.copyOf(production);
        this.rng = rng;
        this.bus = bus;
    }

    /** M2 标准管线：十二阶段全量（经济系统为空实现）。 */
    public static TickPipeline m2(Map<Ids.LocationId, WorldAssembler.Production> production,
                                  RngStreams rng, EventBus bus) {
        return m3(production, rng, bus, null, null);
    }

    /** M3 管线：注入生产与市场系统。 */
    public static TickPipeline m3(Map<Ids.LocationId, WorldAssembler.Production> production,
                                  RngStreams rng, EventBus bus,
                                  fc.sim.spi.ProductionSystem prodSystem,
                                  fc.sim.spi.MarketSystem marketSystem) {
        return new TickPipeline(List.of(
                new TimeStage(),
                new EnvironmentStage(),
                new FactionStage(),
                new PerceiveStage(),
                new DecideStage(),
                new WorkStage(prodSystem),
                new VoyageStage(),
                new ConsumptionStage(),
                new MarketStage(marketSystem),
                new WorldEventStage(),
                new ChronicleStage(),
                new ReportStage()
        ), production, rng, bus);
    }

    /** 执行一个 tick。 */
    public void tick(WorldState state) {
        TickContext ctx = new TickContext(production, rng);
        for (TickStage s : stages) {
            s.apply(state, ctx);
        }
        lastCtx = ctx;
        if (bus != null) {
            // tick 结束后才广播：只读通知，不写状态
            bus.publishAll(ctx.drained());
        }
    }

    /** 阶段名列表（诊断用）。 */
    public List<String> stageNames() {
        return stages.stream().map(TickStage::name).toList();
    }

    /** 上一个 tick 的报告行（未执行过则返回空）。 */
    public List<String> lastReport() {
        return lastCtx == null ? List.of() : lastCtx.reportLines();
    }

    public RngStreams rng() {
        return rng;
    }
}
