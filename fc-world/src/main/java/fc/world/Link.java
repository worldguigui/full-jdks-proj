package fc.world;

import fc.world.Ids.LocationId;

import java.math.BigDecimal;

/**
 * 统一世界规则的第二抽象：链接（Link）。
 *
 * <p>地点之间的可通行边。跨尺度运输共用同一 {@link Link} + {@code Transfer} 抽象——
 * 星域间的曲速航线、行星表面的地面路线、殖民地内部通道是同一类型的三个子类型。</p>
 */
public sealed interface Link permits Link.WarpRoute, Link.SurfaceRoute, Link.InternalLane {

    /** 起点地点。 */
    LocationId from();

    /** 终点地点。 */
    LocationId to();

    /** 通行所需 tick 数。 */
    int travelTicks();

    /** 通行风险（0..1，越大越危险）。 */
    double risk();

    /** 通行成本。 */
    BigDecimal cost();

    // ---- permitted 类型 ----

    /** 曲速航线：星域之间的恒星际航线。 */
    record WarpRoute(LocationId from, LocationId to, int travelTicks, double risk, BigDecimal cost)
            implements Link {}

    /** 地面路线：行星表面的陆上路线。 */
    record SurfaceRoute(LocationId from, LocationId to, int travelTicks, double risk, BigDecimal cost)
            implements Link {}

    /** 内部通道：殖民地/设施内部的通道。 */
    record InternalLane(LocationId from, LocationId to, int travelTicks, double risk, BigDecimal cost)
            implements Link {}
}
