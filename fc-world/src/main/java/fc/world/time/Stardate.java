package fc.world.time;

import java.time.LocalDate;

/**
 * 单一时间轴：Stardate（启程历）。
 *
 * <p>本项目时间语义见 {@code docs/phase3/time-model-spec.md}：Stardate 是「游戏时间」，
 * 由可注入的 {@link java.time.Clock} 驱动的模拟时钟推进，与「现实时间」
 * （{@code System.nanoTime()}）严格分离，二者互不越界。</p>
 *
 * <p>Stardate 是不可变值对象（record），以「启程日起算的天数」为单位，
 * 可序列化、可比较，是存档与编年史的时间基准。</p>
 */
public record Stardate(long day) implements Comparable<Stardate> {

    /** 启程纪元：游戏开局的 Stardate。 */
    public static final Stardate EPOCH = new Stardate(0);

    /** 推进指定天数，返回新 Stardate（不可变）。 */
    public Stardate plusDays(long delta) {
        return new Stardate(day + delta);
    }

    /** 两个 Stardate 之间的天数差（b - this）。 */
    public long daysUntil(Stardate other) {
        return other.day - this.day;
    }

    /** 是否早于（严格小于）另一个 Stardate。 */
    public boolean isBefore(Stardate other) {
        return this.day < other.day;
    }

    /** 是否不晚于另一个 Stardate。 */
    public boolean isAfter(Stardate other) {
        return this.day > other.day;
    }

    /**
     * 以 ISO 日历近似日期表示（用于展示与互转，启程历历法见 {@code ExpeditionChronology}）。
     * 启程日（day=0）对应 {@link #REFERENCE_EPOCH}。
     */
    public LocalDate toIsoDate() {
        return REFERENCE_EPOCH.plusDays(day);
    }

    /** 启程日对应的 ISO 参考日期（世界定义：启程发生在这一天）。 */
    public static final LocalDate REFERENCE_EPOCH = LocalDate.of(2200, 1, 1);

    @Override
    public int compareTo(Stardate o) {
        return Long.compare(this.day, o.day);
    }

    @Override
    public String toString() {
        return "SD " + day;
    }
}
