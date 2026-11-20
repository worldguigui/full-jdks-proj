package fc.world;

import fc.world.Ids.LocationId;
import fc.world.time.Stardate;

import java.util.List;

/**
 * 只读世界视图（WorldView）。
 *
 * <p>提供给 {@link Agent#tick} 与各系统结算器的只读快照，避免决策过程直接改动世界状态。
 * 世界状态只能通过 tick 管线确定的结算器修改。</p>
 */
public interface WorldView {

    /** 当前 Stardate。 */
    Stardate now();

    /** 某地点是否存在。 */
    boolean hasLocation(LocationId id);

    /** 从某地点出发的链接。 */
    List<Link> linksFrom(LocationId id);

    /** 某地点的账本分区视图。 */
    Stock stockAt(LocationId id);
}
