package fc.history.query;

import fc.world.Chronicle;
import fc.world.GameEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 编年史查询条件：时间、类别、类型、地点、主体、全文与分页。
 *
 * <p>查询是只读的；条件之间是 AND。{@link #select(Chronicle)} 先过滤、再按星历稳定排序，
 * 最后应用偏移与条数。SQL 驱动把 WHERE 子句映射到同一模型，保证 CLI 与 JDBC
 * 得到一致的语义。</p>
 */
public record ChronicleQuery(Long fromDay, Long toDay, String category, String type,
                             String location, Long actor, String text,
                             int offset, int limit, boolean descending) {

    public ChronicleQuery {
        if (fromDay != null && toDay != null && fromDay > toDay) {
            throw new IllegalArgumentException("起始星历不能晚于结束星历");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset 不得为负");
        }
        if (limit < 0) {
            throw new IllegalArgumentException("limit 不得为负");
        }
        category = blankToNull(category);
        type = blankToNull(type);
        location = blankToNull(location);
        text = blankToNull(text);
    }

    public static ChronicleQuery all() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 执行查询并返回规范化视图。 */
    public List<EventView> select(Chronicle chronicle) {
        List<GameEvent> events = chronicle.all();
        List<EventView> matched = new ArrayList<>();
        for (GameEvent event : events) {
            if (matches(event)) {
                matched.add(EventView.of(event));
            }
        }
        matched.sort(Comparator.comparingLong(EventView::day));
        if (descending) {
            matched = reversed(matched);
        }
        if (offset >= matched.size()) {
            return List.of();
        }
        int end = limit == 0 ? matched.size() : Math.min(matched.size(), offset + limit);
        return List.copyOf(matched.subList(offset, end));
    }

    /** 满足条件的总数（忽略 offset/limit）。 */
    public int count(Chronicle chronicle) {
        int count = 0;
        for (GameEvent event : chronicle.all()) {
            if (matches(event)) {
                count++;
            }
        }
        return count;
    }

    /** 单事件是否满足全部条件。 */
    public boolean matches(GameEvent event) {
        long day = event.when().day();
        if (fromDay != null && day < fromDay) {
            return false;
        }
        if (toDay != null && day > toDay) {
            return false;
        }
        if (category != null && !matchesCategory(event)) {
            return false;
        }
        if (type != null && !normalizeType(event).equalsIgnoreCase(normalizeType(type))) {
            return false;
        }
        if (location != null && !normalizeLocation("L" + event.where().id())
                .equalsIgnoreCase(normalizeLocation(location))) {
            return false;
        }
        if (actor != null && event.who().stream().noneMatch(a -> a.id() == actor)) {
            return false;
        }
        if (text != null && !containsText(event)) {
            return false;
        }
        return true;
    }

    private boolean matchesCategory(GameEvent event) {
        String actual = EventView.categoryOf(event);
        String wanted = category;
        if (wanted.equalsIgnoreCase(actual)
                || wanted.equalsIgnoreCase(actual + "Event")) {
            return true;
        }
        return wanted.equalsIgnoreCase("All");
    }

    private boolean containsText(GameEvent event) {
        EventView view = EventView.of(event);
        String needle = text.toLowerCase(Locale.ROOT);
        return view.summary().toLowerCase(Locale.ROOT).contains(needle)
                || view.date().toLowerCase(Locale.ROOT).contains(needle)
                || view.actorsCsv().toLowerCase(Locale.ROOT).contains(needle)
                || view.location().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String normalizeType(GameEvent event) {
        return normalizeType(event.getClass().getSimpleName());
    }

    private static String normalizeType(String value) {
        String v = value.strip();
        return v.endsWith("Event") && v.length() > "Event".length()
                ? v.substring(0, v.length() - "Event".length()) : v;
    }

    private static String normalizeLocation(String value) {
        String v = value.strip().toUpperCase(Locale.ROOT);
        return v.startsWith("L") ? v : "L" + v;
    }

    private static List<EventView> reversed(List<EventView> source) {
        List<EventView> out = new ArrayList<>(source);
        java.util.Collections.reverse(out);
        return out;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** 查询构造器；未设置的字段表示不限制。 */
    public static final class Builder {
        private Long fromDay;
        private Long toDay;
        private String category;
        private String type;
        private String location;
        private Long actor;
        private String text;
        private int offset;
        private int limit;
        private boolean descending;

        public Builder fromDay(Long value) {
            this.fromDay = value;
            return this;
        }

        public Builder toDay(Long value) {
            this.toDay = value;
            return this;
        }

        public Builder category(String value) {
            this.category = value;
            return this;
        }

        public Builder type(String value) {
            this.type = value;
            return this;
        }

        public Builder location(String value) {
            this.location = value;
            return this;
        }

        public Builder actor(Long value) {
            this.actor = value;
            return this;
        }

        public Builder text(String value) {
            this.text = value;
            return this;
        }

        public Builder offset(int value) {
            this.offset = value;
            return this;
        }

        public Builder limit(int value) {
            this.limit = value;
            return this;
        }

        public Builder descending(boolean value) {
            this.descending = value;
            return this;
        }

        public ChronicleQuery build() {
            return new ChronicleQuery(fromDay, toDay, category, type, location, actor,
                    text, offset, limit, descending);
        }
    }
}
