package fc.world;

/**
 * 地点知识状态：探索系统的四档认知。
 *
 * <pre>
 * UNKNOWN   完全未知，星图不显示
 * DETECTED  已探测到坐标，只显示名称与大致位置
 * SURVEYED  已抵达或近距离勘察，可见库存与航线
 * CHARTED   已完成测绘，可在该处开展科研与开发
 * </pre>
 */
public enum KnowledgeState {
    UNKNOWN("未知"),
    DETECTED("已探测"),
    SURVEYED("已勘察"),
    CHARTED("已测绘");

    private final String display;

    KnowledgeState(String display) {
        this.display = display;
    }

    public String displayName() {
        return display;
    }

    /** 是否至少达到给定档位。 */
    public boolean atLeast(KnowledgeState other) {
        return ordinal() >= other.ordinal();
    }
}
