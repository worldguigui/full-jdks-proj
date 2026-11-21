package fc.diplomacy;

import fc.config.world.RecipeDef;
import fc.config.world.WorldDefinition;
import fc.sim.spi.GoalSystem;
import fc.sim.tick.TickContext;
import fc.world.ColonyState;
import fc.world.Ids;
import fc.world.KnowledgeState;
import fc.world.ResourceKind;
import fc.world.Treaty;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 阶段目标 D0–D4 判定与面板。
 *
 * <table>
 *   <caption>目标</caption>
 *   <tr><th>D0 生存</th><td>第 10 天起玩家食物/燃料不低于 10 天前，且补给线发过车</td></tr>
 *   <tr><th>D1 立足</th><td>玩家殖民地 ≥ 1 级，与声望 ≥ 20 的势力签贸易协定</td></tr>
 *   <tr><th>D2 扩张</th><td>舰队 ≥ 5 艘，已知地点 ≥ 12，完成一项 Tier 2 科技</td></tr>
 *   <tr><th>D3 自主</th><td>玩家殖民地食物/水日产能 ≥ 日消费，且与势力结盟</td></tr>
 *   <tr><th>D4 终局</th><td>完成星门或信标工程</td></tr>
 * </table>
 *
 * <p>日产能 = 自然产出 + 该地点配方产出 / 配方工期；水培农业使食物产出 +25%。</p>
 */
public final class GoalService implements GoalSystem {

    /** 目标 id（写入状态哈希）。 */
    public static final String D0 = "D0";
    public static final String D1 = "D1";
    public static final String D2 = "D2";
    public static final String D3 = "D3";
    public static final String D4 = "D4";

    private static final List<String> TIER2 = List.of("WARP_COIL", "AUTOMATION", "TRADE_PROTOCOL");

    private final Map<Ids.LocationId, Map<ResourceKind, BigDecimal>> recipeCapacity;
    private final Map<Ids.LocationId, Map<ResourceKind, BigDecimal>> naturalOutput;

    public GoalService(WorldDefinition def) {
        this(def, Map.of());
    }

    public GoalService(WorldDefinition def,
                       Map<Ids.LocationId, Map<ResourceKind, BigDecimal>> naturalOutput) {
        this.naturalOutput = naturalOutput;
        Map<Ids.LocationId, Map<ResourceKind, BigDecimal>> capacity = new TreeMap<>();
        for (RecipeDef r : def.economy().recipes()) {
            int ticks = Math.max(1, r.ticks());
            for (Map.Entry<String, BigDecimal> out : r.outputs().entrySet()) {
                ResourceKind kind;
                try {
                    kind = ResourceKind.goodsFromId(out.getKey());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                capacity.computeIfAbsent(new Ids.LocationId(r.at()), k -> new LinkedHashMap<>())
                        .merge(kind, out.getValue().divide(BigDecimal.valueOf(ticks), 4,
                                java.math.RoundingMode.HALF_UP), BigDecimal::add);
            }
        }
        this.recipeCapacity = capacity;
    }

    @Override
    public List<String> evaluate(WorldState state, TickContext ctx) {
        Map<String, Boolean> status = status(state);
        List<String> newly = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : status.entrySet()) {
            if (e.getValue() && state.markGoalReached(e.getKey())) {
                newly.add(e.getKey() + " " + name(e.getKey()));
            }
        }
        return newly;
    }

    /** 各目标当前是否达成（按 D0..D4 顺序）。 */
    public Map<String, Boolean> status(WorldState state) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        out.put(D0, d0(state));
        out.put(D1, d1(state));
        out.put(D2, d2(state));
        out.put(D3, d3(state));
        out.put(D4, d4(state));
        return out;
    }

    private static boolean d0(WorldState state) {
        long day = state.now().day();
        if (day < 10) {
            return false;
        }
        WorldState.CargoSnapshot baseline = state.cargoSnapshot(day - 10);
        if (baseline == null) {
            return false;
        }
        return state.playerStock(ResourceKind.FOOD).compareTo(baseline.food()) >= 0
                && state.playerStock(ResourceKind.FUEL).compareTo(baseline.fuel()) >= 0
                && state.supplyDispatches() > 0;
    }

    private static boolean d1(WorldState state) {
        boolean colony = state.playerColonies().stream().anyMatch(c -> c.level() >= 1);
        if (!colony) {
            return false;
        }
        for (Ids.FactionId fid : state.treaties().keySet()) {
            if (state.hasTreaty(fid, Treaty.TRADE) && state.reputation(fid) >= 20) {
                return true;
            }
        }
        return false;
    }

    private static boolean d2(WorldState state) {
        boolean fleet = state.graph().ships().size() >= 5;
        int known = state.locationsWithKnowledge(KnowledgeState.DETECTED).size();
        boolean tier2 = TIER2.stream().anyMatch(state::hasTech);
        return fleet && known >= 12 && tier2;
    }

    private boolean d3(WorldState state) {
        boolean alliance = state.treaties().keySet().stream()
                .anyMatch(fid -> state.hasTreaty(fid, Treaty.ALLIANCE));
        if (!alliance) {
            return false;
        }
        BigDecimal foodCapacity = BigDecimal.ZERO;
        BigDecimal waterCapacity = BigDecimal.ZERO;
        BigDecimal consumption = BigDecimal.ZERO;
        for (Ids.LocationId id : state.playerLocationIds()) {
            for (Map.Entry<ResourceKind, BigDecimal> e
                    : recipeCapacity.getOrDefault(id, Map.of()).entrySet()) {
                BigDecimal amount = e.getValue();
                if (e.getKey() == ResourceKind.FOOD && state.hasTech("HYDROPONICS")) {
                    amount = amount.multiply(new BigDecimal("1.25"));
                }
                if (e.getKey() == ResourceKind.FOOD) {
                    foodCapacity = foodCapacity.add(amount);
                } else if (e.getKey() == ResourceKind.WATER) {
                    waterCapacity = waterCapacity.add(amount);
                }
            }
        }
        for (ColonyState c : state.playerColonies()) {
            BigDecimal need = BigDecimal.valueOf(Math.max(1, c.population() / 5));
            foodCapacity = foodCapacity.add(naturalOutput
                    .getOrDefault(c.id(), Map.of()).getOrDefault(ResourceKind.FOOD, BigDecimal.ZERO));
            waterCapacity = waterCapacity.add(naturalOutput
                    .getOrDefault(c.id(), Map.of()).getOrDefault(ResourceKind.WATER, BigDecimal.ZERO));
            consumption = consumption.add(need);
        }
        return consumption.signum() > 0
                && foodCapacity.compareTo(consumption) >= 0
                && waterCapacity.compareTo(consumption) >= 0;
    }

    private static boolean d4(WorldState state) {
        return state.ending() != null || state.projectCompleted("GATE")
                || state.projectCompleted("BEACON");
    }

    /** 目标面板。 */
    public String panel(WorldState state) {
        Map<String, Boolean> status = status(state);
        StringBuilder sb = new StringBuilder("---- 阶段目标 D0–D4 ----\n");
        for (Map.Entry<String, Boolean> e : status.entrySet()) {
            sb.append("  ").append(e.getKey()).append(' ').append(name(e.getKey()))
                    .append("：").append(e.getValue() ? "已达成" : "进行中")
                    .append("　").append(detail(state, e.getKey())).append('\n');
        }
        sb.append("  已知地点 ").append(state.locationsWithKnowledge(KnowledgeState.DETECTED).size())
                .append('/').append(state.graph().placeCount())
                .append("｜舰队 ").append(state.graph().ships().size())
                .append(" 艘｜科技 ").append(state.techs().size())
                .append(" 项｜条约 ").append(state.treaties().values().stream()
                        .mapToInt(java.util.Set::size).sum()).append(" 项\n");
        if (state.ending() != null) {
            sb.append("  ★ 终局已达成：").append(state.ending()).append('\n');
        }
        return sb.toString();
    }

    private static String name(String goal) {
        return switch (goal) {
            case D0 -> "生存";
            case D1 -> "立足";
            case D2 -> "扩张";
            case D3 -> "自主";
            case D4 -> "终局";
            default -> goal;
        };
    }

    private String detail(WorldState state, String goal) {
        long day = state.now().day();
        return switch (goal) {
            case D0 -> "食物 " + state.playerStock(ResourceKind.FOOD)
                    .stripTrailingZeros().toPlainString()
                    + " / 10 天前 "
                    + (state.cargoSnapshot(day - 10) == null ? "无记录"
                            : state.cargoSnapshot(day - 10).food()
                                    .stripTrailingZeros().toPlainString())
                    + "｜燃料 " + state.playerStock(ResourceKind.FUEL)
                            .stripTrailingZeros().toPlainString()
                    + "｜补给发车 " + state.supplyDispatches();
            case D1 -> "殖民地 " + state.playerColonies().size()
                    + " 处｜贸易协定 "
                    + state.treaties().values().stream()
                            .mapToInt(s -> s.contains(Treaty.TRADE) ? 1 : 0).sum();
            case D2 -> "舰队 " + state.graph().ships().size() + "/5｜已知 "
                    + state.locationsWithKnowledge(KnowledgeState.DETECTED).size() + "/12｜Tier2 "
                    + TIER2.stream().filter(state::hasTech).count() + "/1";
            case D3 -> "同盟 "
                    + state.treaties().values().stream()
                            .mapToInt(s -> s.contains(Treaty.ALLIANCE) ? 1 : 0).sum();
            case D4 -> state.ending() == null ? "工程未完成" : state.ending();
            default -> "";
        };
    }
}
