package fc.sim.rng;

import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * 确定性随机流（按子系统派生，互不干扰）。
 *
 * <p>设计依据（game-design §8.3）：同一种子 + 同一操作序列 ⇒ 同一历史。
 * 随机来自 {@link RandomGeneratorFactory} 的**可跳跃算法**，按子系统派生独立流
 * （天气 / 事件 / 主体 / 市场 / 势力），一个子系统的抽样不会扰动另一个。</p>
 *
 * <p>可重放：只需保存算法名 + 主种子，即可重建全部子流。</p>
 */
public final class RngStreams {

    /** 选用可跳跃算法（JDK 17+ 标准实现，跨 JVM 稳定）。 */
    public static final String ALGORITHM = "L64X128MixRandom";

    private final String algorithm;
    private final long masterSeed;
    private final Map<String, RandomGenerator> streams = new HashMap<>();

    private RngStreams(String algorithm, long masterSeed) {
        this.algorithm = algorithm;
        this.masterSeed = masterSeed;
    }

    /** 用主种子创建（默认算法）。 */
    public static RngStreams of(long masterSeed) {
        return new RngStreams(ALGORITHM, masterSeed);
    }

    /** 指定算法名创建（存档恢复时用）。 */
    public static RngStreams of(String algorithm, long masterSeed) {
        return new RngStreams(algorithm, masterSeed);
    }

    public String algorithm() {
        return algorithm;
    }

    public long masterSeed() {
        return masterSeed;
    }

    /**
     * 取得某子系统的随机流（同名多次调用返回同一个流实例，保证推进连续）。
     */
    public RandomGenerator stream(String subsystem) {
        return streams.computeIfAbsent(subsystem, s -> {
            long seed = mix(masterSeed, s.hashCode());
            return RandomGeneratorFactory.of(algorithm).create(seed);
        });
    }

    /** 子系统名常量，避免拼写漂移。 */
    public static final String WEATHER = "weather";
    public static final String EVENTS = "events";
    public static final String AGENTS = "agents";
    public static final String MARKET = "market";
    public static final String FACTIONS = "factions";

    /** SplitMix64 混合：从主种子与子系统名派生独立种子。 */
    private static long mix(long seed, int nameHash) {
        long z = seed + 0x9E3779B97F4A7C15L * (nameHash & 0xFFFFFFFFL);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 便于诊断：某子流的下一个 int（不推进真实流，仅用于日志展示）。 */
    public static long previewSeed(long masterSeed, String subsystem) {
        return mix(masterSeed, subsystem.hashCode());
    }

    /** 便捷：按概率抽样。 */
    public static boolean chance(RandomGenerator rng, double p) {
        return rng.nextDouble() < p;
    }

    /** 便捷：区间抽样。 */
    public static int between(RandomGenerator rng, int minInclusive, int maxInclusive) {
        if (maxInclusive <= minInclusive) {
            return minInclusive;
        }
        return minInclusive + rng.nextInt(maxInclusive - minInclusive + 1);
    }

    /** 用于测试：从主种子直接构造一个独立 SplittableRandom（不影响子流）。 */
    public static SplittableRandom independent(long seed) {
        return new SplittableRandom(seed);
    }
}
