package fc.sim.tick;

import fc.world.Agent;
import fc.world.SkillSet;
import fc.world.WorkUnit;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 阶段 6：生产与工作（WorkUnit 推进，虚拟线程并发 + 固定顺序汇合）。
 *
 * <p>进度按技能倍率 {@code 0.75 + 0.05 × 相关技能} 累加，未绑定技能维度的工作单元
 * 按每名工人每天 1.0 推进（兼容旧数据）。并发只为真实并行计算；
 * <strong>汇合按工作单元 key 排序</strong>，因此结果与串行完全一致。</p>
 *
 * <p>工作单元完成时依次通知所有生产/科研系统，只有识别该 key 的系统会写产出。</p>
 */
public final class WorkStage implements TickStage {

    private final List<fc.sim.spi.ProductionSystem> systems;

    public WorkStage() {
        this(List.of());
    }

    public WorkStage(fc.sim.spi.ProductionSystem production) {
        this(production == null ? List.of() : List.of(production));
    }

    public WorkStage(List<fc.sim.spi.ProductionSystem> systems) {
        this.systems = List.copyOf(systems);
    }

    @Override
    public String name() {
        return "6-work";
    }

    @Override
    public void apply(WorldState state, TickContext ctx) {
        for (fc.sim.spi.ProductionSystem system : systems) {
            system.startUnits(state, ctx);
        }
        List<Map.Entry<String, WorkUnit>> entries = new ArrayList<>(state.workUnits().entrySet());
        if (entries.isEmpty()) {
            return;
        }
        entries.sort(Comparator.comparing(Map.Entry::getKey));

        BigDecimal[] advance = new BigDecimal[entries.size()];
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<BigDecimal>> futures = new ArrayList<>(entries.size());
            for (Map.Entry<String, WorkUnit> e : entries) {
                WorkUnit unit = e.getValue();
                // 无工人的工作单元不推进（不能凭空生产）
                futures.add(executor.submit(() -> advanceOf(state, unit)));
            }
            for (int i = 0; i < futures.size(); i++) {
                advance[i] = futures.get(i).get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("工作阶段被中断", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("工作阶段执行失败", e.getCause());
        }

        for (int i = 0; i < entries.size(); i++) {
            String key = entries.get(i).getKey();
            WorkUnit unit = entries.get(i).getValue();
            if (advance[i].signum() > 0) {
                unit = unit.advance(advance[i]);
            }
            if (unit.done()) {
                for (fc.sim.spi.ProductionSystem system : systems) {
                    system.onComplete(state, ctx, key, unit);
                }
            }
            state.putWorkUnit(key, unit);
        }

        advanceShipyards(state, ctx);
        advanceProjects(state, ctx);
    }

    /** 船坞订单：船坞地点有工人时每天推进 1 天，完工即交付新舰船。 */
    private static void advanceShipyards(WorldState state, TickContext ctx) {
        for (fc.world.ShipOrder order : state.shipOrders()) {
            if (order.status() != fc.world.ShipOrder.Status.BUILDING) {
                continue;
            }
            if (state.agentsAt(order.shipyard()).isEmpty()) {
                continue;
            }
            if (!order.advanceDay()) {
                continue;
            }
            BigDecimal capacity = order.cargoCapacity();
            if (state.hasTech("CARGO_FRAME")) {
                capacity = capacity.add(new BigDecimal("50"));
            }
            fc.world.Ids.ShipId sid = state.nextShipId();
            fc.world.Ids.LocationId locId =
                    new fc.world.Ids.LocationId(sid.id() + 100_000);
            String name = order.name() != null ? order.name()
                    : order.shipType() + " 新舰#" + sid.id();
            fc.world.Location.Ship ship = new fc.world.Location.Ship(
                    locId, name, sid, order.shipType(), capacity, order.crewCapacity(),
                    order.maxHull(), order.baseRiskFactor(), order.shipyard(),
                    fc.world.Stock.empty(), null, order.maxHull());
            state.addShip(ship);
            order.deliver();
            ctx.events().enqueue(new fc.world.GameEvent.ShipDelivered(
                    state.now(), order.shipyard(), order.id(), sid, name));
            ctx.report("船坞交付：" + name + " 停靠 " + order.shipyard().id());
        }
    }

    /** 终局工程：工程地点有工人时每天推进 1 天，完工即进入结局并冻结时间。 */
    private static void advanceProjects(WorldState state, TickContext ctx) {
        if (state.ending() != null) {
            return;
        }
        for (String project : state.startedProjects()) {
            if (state.projectCompleted(project)) {
                continue;
            }
            fc.world.Ids.LocationId at = state.projectLocation(project);
            if (at == null || state.agentsAt(at).isEmpty()) {
                continue;
            }
            int required = state.projectRequiredDays(project);
            if (!state.advanceProject(project, 1, required)) {
                continue;
            }
            ctx.events().enqueue(new fc.world.GameEvent.ProjectCompleted(
                    state.now(), at, project));
            String ending = "GATE".equals(project) ? "星门启航" : "边境信标";
            state.setEnding(ending);
            ctx.events().enqueue(new fc.world.GameEvent.EndingReached(
                    state.now(), at, ending, "工程 " + project + " 完成，边境远征进入新纪元。"));
            ctx.report("★ 终局达成：" + ending + "（时间冻结，仍可查询世界）");
        }
    }

    /** 一天的总推进量：每名工人按相关技能倍率贡献。 */
    private static BigDecimal advanceOf(WorldState state, WorkUnit unit) {
        if (unit.workers().isEmpty()) {
            return BigDecimal.ZERO;
        }
        if (unit.domain() == null) {
            return BigDecimal.valueOf(unit.workers().size());
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (var id : unit.workers()) {
            Agent agent = state.agents().get(id);
            SkillSet skills = agent == null ? SkillSet.of(5) : agent.skills();
            sum = sum.add(skills.workFactor(unit.domain()));
        }
        if (state.hasTech("AUTOMATION")) {
            // 自动化工坊：生产与科研工作速度 +15%。
            sum = sum.multiply(new BigDecimal("1.15"))
                    .setScale(4, java.math.RoundingMode.HALF_UP);
        }
        return sum;
    }
}
