package fc.world;

import fc.world.Ids.DecisionId;
import fc.world.Ids.FactionId;
import fc.world.Ids.LocationId;
import fc.world.time.Stardate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 待决决策：重大事件产生的 2–4 个选项，必须由玩家选择后时间才能继续。
 *
 * <p>选项的后果通过 {@link Effect} + 数值参数描述，由 tick 管线的决策结算器
 * 在玩家确认后应用；未确认前不修改世界状态。决策结果进入编年史，并作为
 * 后续事件的条件。</p>
 */
public final class PendingDecision {

    /** 选项效果类型（可携带 {@link Option#params()} 数值参数）。 */
    public enum Effect {
        /** 向受灾殖民地提供援助：花费信用点，提升声望。 */
        GRANT_AID,
        /** 拒绝/忽略请求：降低声望。 */
        DECLINE,
        /** 向势力缴纳贡金：转移信用点。 */
        PAY_TRIBUTE,
        /** 拒绝纳贡：关系恶化。 */
        REFUSE_TRIBUTE,
        /** 接受延误/损失。 */
        ACCEPT,
        /** 改道：额外延误但避免货物损失。 */
        REROUTE
    }

    /**
     * 一个选项。
     *
     * @param params 效果数值，例如援助金额 "credits"、声望 "reputation"
     */
    public record Option(String id, String label, String description,
                         Effect effect, Map<String, BigDecimal> params) {
        public Option {
            params = Map.copyOf(params);
        }
    }

    private final DecisionId id;
    private final String title;
    private final String description;
    private final LocationId where;
    private final FactionId faction;
    private final Stardate createdAt;
    private final List<Option> options;
    private String chosenOption;

    public PendingDecision(DecisionId id, String title, String description, LocationId where,
                           FactionId faction, Stardate createdAt, List<Option> options) {
        if (options.size() < 2 || options.size() > 4) {
            throw new IllegalArgumentException("决策必须有 2–4 个选项");
        }
        this.id = id;
        this.title = title;
        this.description = description;
        this.where = where;
        this.faction = faction;
        this.createdAt = createdAt;
        this.options = List.copyOf(options);
    }

    public DecisionId id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public LocationId where() {
        return where;
    }

    /** 相关势力；与具体势力无关时为 null。 */
    public FactionId faction() {
        return faction;
    }

    public Stardate createdAt() {
        return createdAt;
    }

    public List<Option> options() {
        return options;
    }

    public boolean resolved() {
        return chosenOption != null;
    }

    public String chosenOption() {
        return chosenOption;
    }

    public Option option(String optionId) {
        for (Option o : options) {
            if (o.id().equals(optionId)) {
                return o;
            }
        }
        return null;
    }

    public void resolve(String optionId) {
        if (option(optionId) == null) {
            throw new IllegalArgumentException("未知选项: " + optionId);
        }
        if (resolved()) {
            throw new IllegalStateException("决策已解决: " + id);
        }
        this.chosenOption = optionId;
    }
}
