package fc.science;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一项科技：所属阶、研究天数、科研数据成本、前置条件与效果说明。
 *
 * <p>前置条件有两种形式，可同时使用：</p>
 * <ul>
 *   <li>{@code prerequisites}：全部前置科技（AND）；</li>
 *   <li>{@code anyOfTier}：至少完成该阶的一项科技（OR），0 表示不要求。</li>
 * </ul>
 *
 * <p>科技表是固定设计数据（不是存档状态）；世界状态只保存已解锁的科技 id。
 * 效果由各系统读取 {@link fc.world.WorldState#hasTech(String)} 应用，
 * 科技本身不直接修改世界。</p>
 */
public record Tech(String id, String name, int tier, int days, BigDecimal dataCost,
                   List<String> prerequisites, int anyOfTier, String effect, Branch branch) {

    /** 科技分支（用于 CLI 分组）。 */
    public enum Branch {
        PRODUCTION("生产"), EXPLORATION("探索"), SOCIETY("社会"), ENDGAME("终局");

        private final String display;

        Branch(String display) {
            this.display = display;
        }

        public String displayName() {
            return display;
        }
    }

    public Tech {
        if (tier < 1 || tier > 3) {
            throw new IllegalArgumentException("科技阶必须为 1..3: " + id);
        }
        if (days < 1) {
            throw new IllegalArgumentException("研究天数必须 ≥ 1: " + id);
        }
        if (dataCost == null || dataCost.signum() < 0) {
            throw new IllegalArgumentException("科研数据成本不得为负: " + id);
        }
        if (anyOfTier < 0 || anyOfTier >= tier) {
            throw new IllegalArgumentException("anyOfTier 必须小于当前阶: " + id);
        }
        prerequisites = List.copyOf(prerequisites);
    }

    /** 兼容：没有“任一前置阶”要求的科技。 */
    public Tech(String id, String name, int days, BigDecimal dataCost,
                List<String> prerequisites, String effect, Branch branch) {
        this(id, name, 1, days, dataCost, prerequisites, 0, effect, branch);
    }

    public boolean root() {
        return prerequisites.isEmpty() && anyOfTier == 0;
    }
}
