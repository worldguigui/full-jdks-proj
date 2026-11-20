package fc.history;

import fc.world.Chronicle;
import fc.world.GameEvent;
import fc.world.Ids.AgentId;
import fc.world.Ids.LocationId;
import fc.world.time.Stardate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 内存编年史实现（M0 骨架）。
 *
 * <p>按 Stardate 顺序记录事件。M5 起加索引、XPath 查询与只读 JDBC/XML/XSLT 报告；
 * 本骨架只保证「事件按时间可查询」。</p>
 */
public final class InMemoryChronicle implements Chronicle {

    private final List<GameEvent> events = new ArrayList<>();

    @Override
    public synchronized void record(GameEvent event) {
        events.add(event);
        events.sort(Comparator.comparing(GameEvent::when));
    }

    @Override
    public synchronized List<GameEvent> since(Stardate start) {
        List<GameEvent> out = new ArrayList<>();
        for (GameEvent e : events) {
            if (!e.when().isBefore(start)) {
                out.add(e);
            }
        }
        return out;
    }

    @Override
    public synchronized List<GameEvent> byActor(AgentId actor) {
        List<GameEvent> out = new ArrayList<>();
        for (GameEvent e : events) {
            if (e.who().contains(actor)) {
                out.add(e);
            }
        }
        return out;
    }

    @Override
    public synchronized List<GameEvent> at(LocationId location) {
        List<GameEvent> out = new ArrayList<>();
        for (GameEvent e : events) {
            if (e.where().equals(location)) {
                out.add(e);
            }
        }
        return out;
    }

    @Override
    public synchronized List<GameEvent> all() {
        return List.copyOf(events);
    }

    @Override
    public synchronized int size() {
        return events.size();
    }
}
