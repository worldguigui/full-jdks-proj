package fc.world;

/**
 * 地点操作工具：在不可变 record 之上做「返回新实例」的替换。
 *
 * <p>库存阶段需要更新某地点的账本分区视图，而 {@link Location} 的 permitted 记录不可变，
 * 因此用 sealed + 模式匹配集中构造同类新实例（避免各处重复 switch）。</p>
 */
public final class LocationOps {

    private LocationOps() {
    }

    /** 返回与给定地点同类但库存被替换的新实例。船（可变）直接改货舱以外的库存视图。 */
    public static Location withStock(Location loc, Stock newStock) {
        return switch (loc) {
            case Location.StarSystem l -> new Location.StarSystem(l.id(), newStock, l.name());
            case Location.Planet l -> new Location.Planet(l.id(), newStock, l.name());
            case Location.OrbitalStation l -> new Location.OrbitalStation(l.id(), newStock, l.name());
            case Location.Anomaly l -> new Location.Anomaly(l.id(), newStock, l.name());
            case Location.Colony l -> new Location.Colony(l.id(), newStock, l.name());
            case Location.Facility l -> new Location.Facility(l.id(), newStock, l.name());
            case Location.Ship l -> l;
            case Location.ExtensionLocation l -> l;
        };
    }

    /** 人类可读的地点种类名。 */
    public static String kindOf(Location loc) {
        return switch (loc) {
            case Location.StarSystem l -> "星系";
            case Location.Planet l -> "行星";
            case Location.OrbitalStation l -> "空间站";
            case Location.Anomaly l -> "异常点";
            case Location.Colony l -> "殖民地";
            case Location.Facility l -> "设施";
            case Location.Ship l -> "舰船";
            case Location.ExtensionLocation l -> "扩展地点";
        };
    }
}
