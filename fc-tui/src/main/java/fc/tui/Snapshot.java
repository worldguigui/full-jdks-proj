package fc.tui;

import fc.world.KnowledgeState;
import fc.world.ResourceKind;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 界面只读视图模型。所有字段在构建时固化，页面渲染不触碰游戏对象，
 * 也不会在绘制过程中推进世界。
 */
public record Snapshot(
        long day,
        String date,
        String credits,
        String ending,
        List<Place> places,
        List<ShipView> ships,
        List<ColonyView> colonies,
        List<FactionView> factions,
        List<EventRow> events,
        List<String> techs,
        List<ResearchJob> researchJobs,
        List<DecisionView> decisions,
        Map<ResourceKind, BigDecimal> playerResources,
        String marketReport,
        String researchReport,
        String technologiesReport,
        int knownPlaces,
        int unknownPlaces,
        String goalsReport) implements Serializable {

    public Snapshot {
        places = List.copyOf(places);
        ships = List.copyOf(ships);
        colonies = List.copyOf(colonies);
        factions = List.copyOf(factions);
        events = List.copyOf(events);
        techs = List.copyOf(techs);
        researchJobs = List.copyOf(researchJobs);
        decisions = List.copyOf(decisions);
        playerResources = Map.copyOf(playerResources);
        marketReport = marketReport == null ? "" : marketReport;
        researchReport = researchReport == null ? "" : researchReport;
        technologiesReport = technologiesReport == null ? "" : technologiesReport;
        goalsReport = goalsReport == null ? "" : goalsReport;
    }

    /** 地点行：kind 为稳定的中文类型名。 */
    public record Place(String id, String name, String kind, KnowledgeState knowledge,
                        String owner, Map<ResourceKind, BigDecimal> stock,
                        List<String> links, boolean playerOwned) implements Serializable {
        public Place {
            stock = Map.copyOf(stock);
            links = List.copyOf(links);
        }
    }

    /** 舰船行。 */
    public record ShipView(String id, String name, String type, String location,
                           int hull, int maxHull, int crewCapacity, String voyage,
                           BigDecimal cargoUsed, BigDecimal cargoCapacity) implements Serializable {
    }

    public record ColonyView(String id, String name, int level, int population,
                             boolean playerOwned, String nextUpgrade) implements Serializable {
    }

    public record FactionView(String name, String kind, int reputation, String treaties) implements Serializable {
    }

    public record EventRow(long day, String category, String type, String summary) implements Serializable {
    }

    public record ResearchJob(String lab, String tech) implements Serializable {
    }

    /** 待决事项：带决策 id 与选项 id，界面直接给出可执行的 decide 命令。 */
    public record DecisionView(String id, String title, String description,
                               List<OptionView> options) implements Serializable {
        public DecisionView {
            options = List.copyOf(options);
        }
    }

    public record OptionView(String id, String label) implements Serializable {
    }
}
