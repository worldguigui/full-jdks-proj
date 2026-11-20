package fc.sim.tick;

import fc.sim.WorldAssembler;
import fc.sim.event.EventBus;
import fc.sim.rng.RngStreams;
import fc.sim.spi.ColonySystem;
import fc.sim.spi.FactionSystem;
import fc.sim.spi.MarketSystem;
import fc.sim.spi.ProductionSystem;
import fc.world.Ids;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Tick 管线（固定十二阶段，按依赖 DAG 排序）。
 *
 * <table>
 *   <caption>阶段顺序</caption>
 *   <tr><td>1</td><td>时间推进</td><td>2</td><td>环境与生态</td><td>3</td><td>势力 AI</td></tr>
 *   <tr><td>4</td><td>主体感知</td><td>5</td><td>主体决策</td><td>6</td><td>生产与工作（虚拟线程）</td></tr>
 *   <tr><td>7</td><td>航行与物流</td><td>8</td><td>消耗与维护</td><td>9</td><td>市场与财务</td></tr>
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
    private final BigDecimal loanDailyRate;
    private TickContext lastCtx;

    private TickPipeline(List<TickStage> stages,
                         Map<Ids.LocationId, WorldAssembler.Production> production,
                         RngStreams rng, EventBus bus, BigDecimal loanDailyRate) {
        this.stages = List.copyOf(stages);
        this.production = Map.copyOf(production);
        this.rng = rng;
        this.bus = bus;
        this.loanDailyRate = loanDailyRate;
    }

    /**
     * 标准管线：生产、市场、势力、殖民地与贷款利率全部注入。
     * 尚未实现的系统传 {@code null}，但十二阶段位置与顺序不变。
     */
    public static TickPipeline m3(Map<Ids.LocationId, WorldAssembler.Production> production,
                                  RngStreams rng, EventBus bus,
                                  ProductionSystem prodSystem,
                                  MarketSystem marketSystem,
                                  FactionSystem factionSystem,
                                  ColonySystem colonySystem,
                                  BigDecimal loanDailyRate) {
        return new TickPipeline(List.of(
                new TimeStage(),
                new EnvironmentStage(colonySystem),
                new FactionStage(factionSystem),
                new PerceiveStage(),
                new DecideStage(),
                new WorkStage(prodSystem),
                new VoyageStage(),
                new ConsumptionStage(),
                new MarketStage(marketSystem),
                new WorldEventStage(),
                new ChronicleStage(),
                new ReportStage()
        ), production, rng, bus, loanDailyRate == null ? BigDecimal.ZERO : loanDailyRate);
    }

    /** 执行一个 tick。 */
    public void tick(WorldState state) {
        TickContext ctx = new TickContext(production, rng, loanDailyRate);
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
