package fc.world;

import fc.world.Ids.AgentId;

import java.util.List;

/**
 * 统一世界规则的第四抽象：工作单元（WorkUnit）。
 *
 * <p>生产、维修、科研、施工共用的抽象。一个工作单元引用一份配方（Recipe），
 * 记录进度与参与工人，支持多人协作。</p>
 */
public final class WorkUnit {

    private final String recipeName;
    private final int progress;
    private final int totalTicks;
    private final List<AgentId> workers;

    public WorkUnit(String recipeName, int progress, int totalTicks, List<AgentId> workers) {
        this.recipeName = recipeName;
        this.progress = progress;
        this.totalTicks = totalTicks;
        this.workers = List.copyOf(workers);
    }

    public String recipeName() {
        return recipeName;
    }

    public int progress() {
        return progress;
    }

    public int totalTicks() {
        return totalTicks;
    }

    public List<AgentId> workers() {
        return workers;
    }

    /** 是否已完成。 */
    public boolean done() {
        return progress >= totalTicks;
    }

    /** 推进一格，返回新 WorkUnit。 */
    public WorkUnit advance() {
        return new WorkUnit(recipeName, Math.min(progress + 1, totalTicks), totalTicks, workers);
    }
}
