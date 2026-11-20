package fc.world;

import fc.world.Ids.LocationId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 地点图（LocationGraph）。
 *
 * <p>星域图上的节点（Location）与边（Link）。{@code Colony} 为节点，{@code Facility}
 * 归属 Colony 子图。M1 起由配置（{@code fc-config}）驱动生成 16 个地点。</p>
 */
public final class LocationGraph {

    private final Map<LocationId, Location> nodes;
    private final List<Link> links;

    public LocationGraph(Map<LocationId, Location> nodes, List<Link> links) {
        this.nodes = new HashMap<>(nodes);
        this.links = new ArrayList<>(links);
    }

    /** 空图。 */
    public static LocationGraph empty() {
        return new LocationGraph(Map.of(), List.of());
    }

    /** 全部地点（只读）。 */
    public Map<LocationId, Location> nodes() {
        return Collections.unmodifiableMap(nodes);
    }

    /** 全部链接（只读）。 */
    public List<Link> links() {
        return Collections.unmodifiableList(links);
    }

    /** 某地点是否存在。 */
    public boolean contains(LocationId id) {
        return nodes.containsKey(id);
    }

    /** 获取某地点（不存在返回 null）。 */
    public Location get(LocationId id) {
        return nodes.get(id);
    }

    /** 替换某地点（库存阶段更新账本视图时用）。 */
    public void replace(Location location) {
        if (!nodes.containsKey(location.id())) {
            throw new IllegalArgumentException("地点不存在，无法替换: " + location.id());
        }
        nodes.put(location.id(), location);
    }

    /** 全部舰船（图节点中类型为 Ship 的）。 */
    public java.util.List<Location.Ship> ships() {
        java.util.List<Location.Ship> out = new ArrayList<>();
        for (Location l : nodes.values()) {
            if (l instanceof Location.Ship s) {
                out.add(s);
            }
        }
        return out;
    }

    /** 静态地点数量（不含舰船这类移动 Location）。 */
    public int placeCount() {
        int n = 0;
        for (Location l : nodes.values()) {
            if (!(l instanceof Location.Ship)) {
                n++;
            }
        }
        return n;
    }

    /** 从某地点出发的链接。 */
    public List<Link> linksFrom(LocationId id) {
        List<Link> out = new ArrayList<>();
        for (Link l : links) {
            if (l.from().equals(id)) {
                out.add(l);
            }
        }
        return out;
    }

    /** 地点数量。 */
    public int size() {
        return nodes.size();
    }
}
