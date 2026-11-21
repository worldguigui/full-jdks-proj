package fc.world;

/** 玩家与势力之间的条约类型。 */
public enum Treaty {
    /** 贸易协定：本地交易税减半。 */
    TRADE("贸易协定"),
    /** 互不侵犯：降低冲突升级概率。 */
    NON_AGGRESSION("互不侵犯"),
    /** 同盟：舰队力量计入冲突结算。 */
    ALLIANCE("同盟");

    private final String display;

    Treaty(String display) {
        this.display = display;
    }

    public String displayName() {
        return display;
    }
}
