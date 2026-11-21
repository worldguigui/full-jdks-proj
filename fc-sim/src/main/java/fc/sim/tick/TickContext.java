package fc.sim.tick;

import fc.sim.WorldAssembler;
import fc.sim.event.EventQueue;
import fc.sim.rng.RngStreams;
import fc.world.Ids;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tick 执行上下文：产出表、随机流、事件队列、报告缓冲、环境量与贷款利率。
 *
 * <p>一个 tick 一个实例；阶段间通过本对象传递只读参数与累积结果（不写世界状态以外的东西）。</p>
 */
public final class TickContext {

    private final Map<Ids.LocationId, WorldAssembler.Production> production;
    private final RngStreams rng;
    private final BigDecimal loanDailyRate;
    private final List<fc.config.world.WorldDefinition.EventDef> eventRules;
    private final EventQueue events = new EventQueue();
    private final List<String> report = new ArrayList<>();
    private List<fc.world.GameEvent> drained = List.of();
    private int weather;

    public TickContext(Map<Ids.LocationId, WorldAssembler.Production> production, RngStreams rng) {
        this(production, rng, BigDecimal.ZERO);
    }

    public TickContext(Map<Ids.LocationId, WorldAssembler.Production> production, RngStreams rng,
                       BigDecimal loanDailyRate) {
        this(production, rng, loanDailyRate, List.of());
    }

    public TickContext(Map<Ids.LocationId, WorldAssembler.Production> production, RngStreams rng,
                       BigDecimal loanDailyRate,
                       List<fc.config.world.WorldDefinition.EventDef> eventRules) {
        this.production = Map.copyOf(production);
        this.rng = rng;
        this.loanDailyRate = loanDailyRate;
        this.eventRules = List.copyOf(eventRules);
    }

    public Map<Ids.LocationId, WorldAssembler.Production> production() {
        return production;
    }

    public RngStreams rng() {
        return rng;
    }

    /** 玩家贷款日利率（由配置提供；0 表示不计息）。 */
    public BigDecimal loanDailyRate() {
        return loanDailyRate;
    }

    /** 配置化事件规则（为空时使用旧的内置风暴参数）。 */
    public List<fc.config.world.WorldDefinition.EventDef> eventRules() {
        return eventRules;
    }

    public EventQueue events() {
        return events;
    }

    /** 环境强度采样 0..100（天气/星象）。 */
    public int weather() {
        return weather;
    }

    public void setWeather(int weather) {
        this.weather = Math.max(0, Math.min(100, weather));
    }

    /** 编年史阶段落库的事件副本（供 tick 结束后广播，只读）。 */
    public List<fc.world.GameEvent> drained() {
        return drained;
    }

    /** 由编年史阶段设置已落库事件。 */
    public void setDrained(List<fc.world.GameEvent> drained) {
        this.drained = List.copyOf(drained);
    }

    /** 追加一条回合报告。 */
    public void report(String line) {
        report.add(line);
    }

    public List<String> reportLines() {
        return List.copyOf(report);
    }
}
