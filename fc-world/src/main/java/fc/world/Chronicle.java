package fc.world;

import fc.world.Ids.AgentId;
import fc.world.Ids.LocationId;
import fc.world.time.Stardate;

import java.util.List;

/**
 * 编年史（Chronicle）——接口在 fc-world，实现在 fc-history。
 *
 * <p>世界的事件史（单一事件史，与单一时间轴对应）。事件按 Stardate 顺序记录，
 * 是玩家查询、导出报告、审计回放的基础。</p>
 */
public interface Chronicle {

    /** 记录一个事件（按 Stardate 顺序）。 */
    void record(GameEvent event);

    /** 从指定 Stardate 起的事件（按时间升序）。 */
    List<GameEvent> since(Stardate start);

    /** 某行为主体参与的事件。 */
    List<GameEvent> byActor(AgentId actor);

    /** 某地点发生的事件。 */
    List<GameEvent> at(LocationId location);

    /** 全部事件（按时间升序）。 */
    List<GameEvent> all();

    /** 事件总数。 */
    int size();
}
