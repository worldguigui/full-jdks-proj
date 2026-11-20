package fc.world;

import fc.world.Ids.LocationId;
import fc.world.time.Stardate;

import java.math.BigDecimal;

/**
 * 统一世界规则的第一抽象：地点（Location）。
 *
 * <p>星域与殖民地是「同一套规则的不同尺度」——{@link Location} 是所有空间实体的根，
 * 尺度差异只是子类型与参数（任务书硬要求）。</p>
 *
 * <p><strong>sealed 约束</strong>：sealed 根与全部 permitted 类型必须位于同一 JPMS 模块
 * （{@code fc-world}），这是 JLS §8.1.6 的要求。领域模块（fleet/colony 等）只提供行为，
 * 不新增 sealed 子类型；扩展走 {@code non-sealed} 的 {@link ExtensionLocation}。</p>
 */
public sealed interface Location
        permits Location.StarSystem, Location.Planet, Location.OrbitalStation,
                Location.Anomaly, Location.Colony, Location.Facility,
                Location.Ship, Location.ExtensionLocation {

    /** 地点标识（进入 LocationGraph 的唯一身份）。 */
    LocationId id();

    /** 该地点的账本分区视图（舰船返回货舱）。 */
    Stock stock();

    /** 地点名称（展示用）。 */
    String name();

    /**
     * 返回库存更新后的地点视图。
     *
     * <p>不可变地点（行星/殖民地等）返回新记录；舰船直接更新货舱并返回自身。
     * 账本是权威数据，本方法只负责让地点持有的视图与账本同步。</p>
     */
    default Location withStock(Stock next) {
        return switch (this) {
            case StarSystem s -> new StarSystem(s.id(), next, s.name());
            case Planet p -> new Planet(p.id(), next, p.name());
            case OrbitalStation o -> new OrbitalStation(o.id(), next, o.name());
            case Anomaly a -> new Anomaly(a.id(), next, a.name());
            case Colony c -> new Colony(c.id(), next, c.name());
            case Facility f -> new Facility(f.id(), next, f.name());
            case Ship s -> {
                s.setCargo(next);
                yield s;
            }
            case ExtensionLocation e -> throw new UnsupportedOperationException(
                    "扩展地点必须自行实现 withStock: " + e.id());
        };
    }

    // ---- permitted 类型（均为 fc-world 内的 record/final class） ----

    /** 恒星系：星域图上的节点，包含行星与轨道站。 */
    record StarSystem(LocationId id, Stock stock, String name) implements Location {}

    /** 行星：恒星系内的地点。 */
    record Planet(LocationId id, Stock stock, String name) implements Location {}

    /** 轨道空间站：绕恒星/行星运行的地点。 */
    record OrbitalStation(LocationId id, Stock stock, String name) implements Location {}

    /** 异常点：特殊空间现象地点。 */
    record Anomaly(LocationId id, Stock stock, String name) implements Location {}

    /** 殖民地：行星/站上的定居点（节点尺度）。 */
    record Colony(LocationId id, Stock stock, String name) implements Location {}

    /** 设施：殖民地内的生产/维修/科研设施（子图尺度）。 */
    record Facility(LocationId id, Stock stock, String name) implements Location {}

    /**
     * 移动地点：舰船（船员 + 货舱 + 在途状态）。
     * 船是 Location 的子类型，因此也进入 LocationGraph。
     *
     * <p>可变性说明：{@code voyage}/{@code parent}/{@code cargo} 会随 tick 管线推进而改变。
     * 这些状态只允许由 tick 阶段（时间/移动/库存）通过本类的受控方法修改，
     * 其它代码应只读。M0 时三者均为 final，M1 起为支持航行改为受控可变。</p>
     */
    final class Ship implements Location {
        private final LocationId id;
        private final String name;
        private final Ids.ShipId shipId;
        private LocationId parent;
        private Stock cargo;
        private VoyageProgress voyage;

        public Ship(LocationId id, String name, Ids.ShipId shipId,
                    LocationId parent, Stock cargo, VoyageProgress voyage) {
            this.id = id;
            this.name = name;
            this.shipId = shipId;
            this.parent = parent;
            this.cargo = cargo;
            this.voyage = voyage;
        }

        @Override
        public LocationId id() {
            return id;
        }

        /** 舰船没有独立仓储，货舱就是它的账本分区。 */
        @Override
        public Stock stock() {
            return cargo;
        }

        @Override
        public String name() {
            return name;
        }

        public Ids.ShipId shipId() {
            return shipId;
        }

        /** 当前所在（或出发）地点。在航时仍为出发地，抵达后更新为目标地。 */
        public LocationId parent() {
            return parent;
        }

        public Stock cargo() {
            return cargo;
        }

        /** 在途进度；未在航时为 null。 */
        public VoyageProgress voyage() {
            return voyage;
        }

        /** 是否在航。 */
        public boolean inVoyage() {
            return voyage != null && !voyage.arrived();
        }

        // ---- 受控修改（仅 tick 阶段调用） ----

        /**
         * 启航：只设置在途进度。
         * 燃料由 {@code VoyageService} 通过权威账本扣除，避免绕过守恒账本。
         */
        public void beginVoyage(VoyageProgress progress) {
            this.voyage = progress;
        }

        /** 推进一格在途进度；返回是否已抵达。 */
        public boolean advanceVoyage() {
            if (voyage == null) {
                return false;
            }
            VoyageProgress next = new VoyageProgress(
                    voyage.from(), voyage.to(), voyage.elapsedTicks() + 1,
                    voyage.totalTicks(), voyage.departed());
            this.voyage = next;
            return next.arrived();
        }

        /** 抵达：更新所在位置并清除在途状态。 */
        public void arrive() {
            if (voyage != null) {
                this.parent = voyage.to();
                this.voyage = null;
            }
        }

        /** 变更货舱（由 {@link WorldState} 在账本变更后同步）。 */
        public void setCargo(Stock newCargo) {
            this.cargo = newCargo;
        }

        /** 风暴等事件造成的延误：延长预计抵达天数。 */
        public void delayVoyage(int extraTicks) {
            if (voyage != null && extraTicks > 0) {
                this.voyage = new VoyageProgress(voyage.from(), voyage.to(),
                        voyage.elapsedTicks(), voyage.totalTicks() + extraTicks, voyage.departed());
            }
        }

        /** 剩余航程天数（未在航为 0）。 */
        public int remainingTicks() {
            return voyage == null ? 0 : Math.max(0, voyage.totalTicks() - voyage.elapsedTicks());
        }
    }

    /** mod 扩展口：允许外部模块新增 Location 子类型。 */
    non-sealed interface ExtensionLocation extends Location {}

    /** 在途进度：舰船航行状态（M1 填充航行语义）。 */
    record VoyageProgress(LocationId from, LocationId to, int elapsedTicks, int totalTicks,
                          Stardate departed) {
        /** 是否已抵达（或未在航）。 */
        public boolean arrived() {
            return elapsedTicks >= totalTicks;
        }
    }
}
