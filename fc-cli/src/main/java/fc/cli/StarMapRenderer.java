package fc.cli;

import fc.world.Ids;
import fc.world.Link;
import fc.world.Location;
import fc.world.LocationGraph;
import fc.world.ResourceKind;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ASCII 星图渲染器：把世界图渲染成终端可读的多行文本（分层显示星系 → 行星/殖民地 → 设施）。
 */
public final class StarMapRenderer {

    private StarMapRenderer() {
    }

    /** 渲染整张星图。 */
    public static String render(WorldState state) {
        LocationGraph graph = state.graph();
        StringBuilder sb = new StringBuilder();
        sb.append("================ 星图（").append(graph.placeCount()).append(" 个地点 / ")
                .append(graph.ships().size()).append(" 艘舰船）================\n");

        Map<Ids.LocationId, List<String>> shipsAt = shipsIndex(graph);

        // 1. 星系树
        for (Location loc : graph.nodes().values()) {
            if (!(loc instanceof Location.StarSystem)) {
                continue;
            }
            appendNode(sb, loc, "", shipsAt);
            for (Ids.LocationId childId : state.childrenOf(loc.id())) {
                Location child = graph.get(childId);
                if (child == null) {
                    continue;
                }
                appendNode(sb, child, "  ├ ", shipsAt);
                for (Ids.LocationId grandId : state.childrenOf(childId)) {
                    Location grand = graph.get(grandId);
                    if (grand != null) {
                        appendNode(sb, grand, "  │   └ ", shipsAt);
                    }
                }
            }
            sb.append("      航线: ").append(linkSummary(graph, loc.id())).append('\n');
        }

        // 2. 独立异常点
        boolean any = false;
        for (Location loc : graph.nodes().values()) {
            if (loc instanceof Location.Anomaly && state.parentOf(loc.id()) == null) {
                if (!any) {
                    sb.append("\n---- 异常点 ----\n");
                    any = true;
                }
                appendNode(sb, loc, "", shipsAt);
                sb.append("      航线: ").append(linkSummary(graph, loc.id())).append('\n');
            }
        }

        // 3. 舰队位置
        sb.append("\n---- 舰队 ----\n");
        for (Location.Ship s : graph.ships()) {
            sb.append("  ◈ ").append(s.name()).append("(#").append(s.shipId().id()).append(") ");
            if (s.inVoyage()) {
                sb.append("在航 ").append(s.voyage().from().id()).append(" -> ").append(s.voyage().to().id())
                        .append(" (").append(s.voyage().elapsedTicks()).append('/')
                        .append(s.voyage().totalTicks()).append(" 天)");
            } else {
                Location at = graph.get(s.parent());
                sb.append("停泊于 ").append(at == null ? s.parent().id() : at.name());
            }
            sb.append("  燃料=").append(s.cargo().amount("fuel").stripTrailingZeros().toPlainString()).append('\n');
        }
        return sb.toString();
    }

    private static Map<Ids.LocationId, List<String>> shipsIndex(LocationGraph graph) {
        Map<Ids.LocationId, List<String>> shipsAt = new HashMap<>();
        for (Location.Ship s : graph.ships()) {
            if (s.inVoyage()) {
                continue;
            }
            shipsAt.computeIfAbsent(s.parent(), k -> new ArrayList<>()).add("◈" + s.name());
        }
        return shipsAt;
    }

    private static void appendNode(StringBuilder sb, Location loc, String indent,
                                   Map<Ids.LocationId, List<String>> shipsAt) {
        sb.append(indent)
                .append('[').append(loc.id().id()).append("] ")
                .append(loc.name())
                .append(' ').append(kindOf(loc));
        Map<ResourceKind, BigDecimal> amounts = new EnumMap<>(ResourceKind.class);
        for (ResourceKind kind : ResourceKind.GOODS) {
            BigDecimal v = loc.stock().amount(kind);
            if (v.signum() != 0) {
                amounts.put(kind, v);
            }
        }
        if (!amounts.isEmpty()) {
            sb.append("  库:").append(stockSummary(amounts));
        }
        List<String> ships = shipsAt.get(loc.id());
        if (ships != null) {
            sb.append("  ").append(String.join(" ", ships));
        }
        sb.append('\n');
    }

    private static String stockSummary(Map<ResourceKind, BigDecimal> amounts) {
        StringBuilder sb = new StringBuilder();
        amounts.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(k.id()).append('=').append(v.stripTrailingZeros().toPlainString());
        });
        return sb.toString();
    }

    /** 人类可读的地点种类名（含扩展地点兜底）。 */
    private static String kindOf(Location loc) {
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

    private static String linkSummary(LocationGraph graph, Ids.LocationId from) {
        List<String> parts = new ArrayList<>();
        for (Link l : graph.links()) {
            Ids.LocationId other;
            if (l.from().equals(from)) {
                other = l.to();
            } else if (l.to().equals(from)) {
                other = l.from();
            } else {
                continue;
            }
            Location o = graph.get(other);
            parts.add("->" + (o == null ? String.valueOf(other.id()) : o.name())
                    + "(" + l.travelTicks() + "天,油" + l.cost().stripTrailingZeros().toPlainString() + ")");
        }
        return parts.isEmpty() ? "（无）" : String.join("  ", parts);
    }
}
