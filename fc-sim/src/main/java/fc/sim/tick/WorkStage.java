package fc.sim.tick;

import fc.world.WorldState;
import fc.world.WorkUnit;

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
 * <p>并发只为真实并行计算（多人协作的产出评估）；<strong>汇合按工作单元 key 排序</strong>，
 * 因此结果与串行完全一致——这是保证确定性的关键约束（game-design §8.3）。</p>
 */
public final class WorkStage implements TickStage {

    private final fc.sim.spi.ProductionSystem production;

    public WorkStage() {
        this(null);
    }

    public WorkStage(fc.sim.spi.ProductionSystem production) {
        this.production = production;
    }

    @Override
    public String name() {
        return "6-work";
    }

    @Override
    public void apply(WorldState state, TickContext ctx) {
        if (production != null) {
            production.startUnits(state, ctx);
        }
        List<Map.Entry<String, WorkUnit>> entries = new ArrayList<>(state.workUnits().entrySet());
        if (entries.isEmpty()) {
            return;
        }
        entries.sort(Comparator.comparing(Map.Entry::getKey));

        int[] advance = new int[entries.size()];
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> futures = new ArrayList<>(entries.size());
            for (Map.Entry<String, WorkUnit> e : entries) {
                // 无工人的工作单元不推进（不能凭空生产）
                futures.add(executor.submit(() -> e.getValue().workers().size()));
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
            for (int step = 0; step < advance[i] && !unit.done(); step++) {
                unit = unit.advance();
            }
            if (unit.done() && production != null) {
                production.onComplete(state, ctx, key, unit);
            }
            state.putWorkUnit(key, unit);
        }
    }
}
