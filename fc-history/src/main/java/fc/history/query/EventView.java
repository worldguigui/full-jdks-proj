package fc.history.query;

import fc.world.GameEvent;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 编年史查询的规范化行：把 sealed 事件压成可排序、可导出、可映射到列的结构。
 *
 * <p>标识使用稳定的人类可读形式：地点 {@code L<id>}、主体 {@code A<id>}。
 * 事件类别来自 {@link GameEvent} 的 permitted 子接口，而不是具体 record 名称。</p>
 */
public record EventView(long day, String date, String type, String category,
                        String location, List<String> actors, String cause,
                        String outcome, String summary) {

    public EventView {
        actors = List.copyOf(actors);
    }

    /** 从领域事件生成视图；不修改事件，也不依赖事件内部实现细节。 */
    public static EventView of(GameEvent event) {
        List<String> actors = event.who().stream()
                .map(a -> "A" + a.id())
                .collect(Collectors.toList());
        String cause = nullToEmpty(event.cause());
        String outcome = nullToEmpty(event.outcome());
        String type = event.getClass().getSimpleName();
        String summary = Stream.of(type, cause, outcome)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(" · "));
        return new EventView(event.when().day(), event.when().toIsoDate().toString(),
                type, categoryOf(event), "L" + event.where().id(), actors,
                cause, outcome, summary);
    }

    /** 事件类别（子接口名去掉 Event 后缀）。 */
    public static String categoryOf(GameEvent event) {
        if (event instanceof GameEvent.WorldEvent) {
            return "World";
        }
        if (event instanceof GameEvent.FleetEvent) {
            return "Fleet";
        }
        if (event instanceof GameEvent.CrewEvent) {
            return "Crew";
        }
        if (event instanceof GameEvent.ColonyEvent) {
            return "Colony";
        }
        if (event instanceof GameEvent.MarketEvent) {
            return "Market";
        }
        if (event instanceof GameEvent.DiplomacyEvent) {
            return "Diplomacy";
        }
        if (event instanceof GameEvent.DiscoveryEvent) {
            return "Discovery";
        }
        return "Extension";
    }

    /** 主体列：逗号分隔的 {@code A<id>}。 */
    public String actorsCsv() {
        return String.join(",", actors);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
