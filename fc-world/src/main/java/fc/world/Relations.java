package fc.world;

import fc.world.Ids.FactionId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 势力关系矩阵：无方向的成对关系值，范围 -100..100。
 *
 * <p>只保存 {@code a < b} 的键，读取时自动归一，避免同一关系出现两份状态。</p>
 */
public final class Relations {

    private final Map<String, Integer> values = new LinkedHashMap<>();

    private static String key(FactionId a, FactionId b) {
        long low = Math.min(a.id(), b.id());
        long high = Math.max(a.id(), b.id());
        return low + ":" + high;
    }

    public synchronized int between(FactionId a, FactionId b) {
        if (a.equals(b)) {
            return 100;
        }
        return values.getOrDefault(key(a, b), 0);
    }

    public synchronized void set(FactionId a, FactionId b, int value) {
        if (a.equals(b)) {
            return;
        }
        values.put(key(a, b), Math.max(-100, Math.min(100, value)));
    }

    public synchronized void adjust(FactionId a, FactionId b, int delta) {
        set(a, b, between(a, b) + delta);
    }

    /** 快照（诊断/哈希用）。 */
    public synchronized Map<String, Integer> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public synchronized void clear() {
        values.clear();
    }
}
