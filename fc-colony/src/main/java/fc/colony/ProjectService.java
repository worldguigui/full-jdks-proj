package fc.colony;

import fc.world.ColonyState;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Location;
import fc.world.ResourceKind;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 终局工程服务：星门（GATE）与边境信标（BEACON）。
 *
 * <p>星门在异常点启动，需要星门理论、科研数据与设备；信标在玩家 3 级殖民地启动，
 * 需要信标理论、材料与设备。启动后另一项锁定；工程地点有工人时由工作阶段
 * 按天推进，完成后进入结局并冻结时间。</p>
 */
public final class ProjectService {

    public static final String GATE = "GATE";
    public static final String BEACON = "BEACON";

    public static final int GATE_DAYS = 12;
    public static final int BEACON_DAYS = 10;
    public static final BigDecimal GATE_RESEARCH = new BigDecimal("8");
    public static final BigDecimal GATE_EQUIPMENT = new BigDecimal("8");
    public static final BigDecimal BEACON_MATERIAL = new BigDecimal("12");
    public static final BigDecimal BEACON_EQUIPMENT = new BigDecimal("8");

    private ProjectService() {
    }

    public record Result(boolean ok, String message) {
    }

    /** 启动星门工程。 */
    public static Result startGate(WorldState state) {
        return start(state, GATE);
    }

    /** 启动信标工程。 */
    public static Result startBeacon(WorldState state) {
        return start(state, BEACON);
    }

    public static Result start(WorldState state, String project) {
        if (state.ending() != null) {
            return new Result(false, "已进入结局：" + state.ending());
        }
        if (!state.startedProjects().isEmpty()) {
            return new Result(false, "已有终局工程在进行："
                    + String.join(",", state.startedProjects()) + "（另一项已锁定）");
        }
        return switch (project) {
            case GATE -> startGateInternal(state);
            case BEACON -> startBeaconInternal(state);
            default -> new Result(false, "未知工程: " + project);
        };
    }

    private static Result startGateInternal(WorldState state) {
        if (!state.hasTech("GATE_THEORY")) {
            return new Result(false, "需要先解锁科技 GATE_THEORY（星门理论）");
        }
        Location.Anomaly anomaly = null;
        for (Location l : state.graph().nodes().values()) {
            if (l instanceof Location.Anomaly a
                    && (anomaly == null || a.id().id() < anomaly.id().id())) {
                anomaly = a;
            }
        }
        if (anomaly == null) {
            return new Result(false, "世界中不存在异常点，无法启动星门");
        }
        if (state.goods(anomaly.id(), ResourceKind.RESEARCH).compareTo(GATE_RESEARCH) < 0
                || state.goods(anomaly.id(), ResourceKind.EQUIPMENT)
                        .compareTo(GATE_EQUIPMENT) < 0) {
            return new Result(false, "异常点物资不足：需要 "
                    + GATE_RESEARCH.stripTrailingZeros().toPlainString() + " research、"
                    + GATE_EQUIPMENT.stripTrailingZeros().toPlainString() + " equipment");
        }
        state.consume(anomaly.id(), ResourceKind.RESEARCH, GATE_RESEARCH, "星门工程");
        state.consume(anomaly.id(), ResourceKind.EQUIPMENT, GATE_EQUIPMENT, "星门工程");
        state.startProject(GATE, anomaly.id(), GATE_DAYS);
        state.chronicle().record(new GameEvent.ProjectStarted(
                state.now(), anomaly.id(), GATE, GATE_DAYS,
                ResourceKind.RESEARCH, GATE_RESEARCH));
        return new Result(true, "星门工程启动于 " + anomaly.name() + "，预计 "
                + GATE_DAYS + " 天；需要工人留在该地点推进。");
    }

    private static Result startBeaconInternal(WorldState state) {
        if (!state.hasTech("BEACON_THEORY")) {
            return new Result(false, "需要先解锁科技 BEACON_THEORY（信标理论）");
        }
        ColonyState target = null;
        for (ColonyState c : state.playerColonies()) {
            if (c.level() >= 3 && (target == null || c.id().id() < target.id().id())) {
                target = c;
            }
        }
        if (target == null) {
            return new Result(false, "需要一处玩家 3 级殖民地");
        }
        if (state.goods(target.id(), ResourceKind.MATERIAL).compareTo(BEACON_MATERIAL) < 0
                || state.goods(target.id(), ResourceKind.EQUIPMENT)
                        .compareTo(BEACON_EQUIPMENT) < 0) {
            return new Result(false, "殖民地物资不足：需要 "
                    + BEACON_MATERIAL.stripTrailingZeros().toPlainString() + " material、"
                    + BEACON_EQUIPMENT.stripTrailingZeros().toPlainString() + " equipment");
        }
        state.consume(target.id(), ResourceKind.MATERIAL, BEACON_MATERIAL, "信标工程");
        state.consume(target.id(), ResourceKind.EQUIPMENT, BEACON_EQUIPMENT, "信标工程");
        state.startProject(BEACON, target.id(), BEACON_DAYS);
        state.chronicle().record(new GameEvent.ProjectStarted(
                state.now(), target.id(), BEACON, BEACON_DAYS,
                ResourceKind.MATERIAL, BEACON_MATERIAL));
        return new Result(true, "边境信标工程启动于殖民地 " + target.id().id() + "，预计 "
                + BEACON_DAYS + " 天；需要工人留在该地点推进。");
    }

    /** 工程面板。 */
    public static String panel(WorldState state) {
        StringBuilder sb = new StringBuilder("---- 终局工程 ----\n");
        sb.append("  星门：").append(state.hasTech("GATE_THEORY") ? "已解锁" : "需要 GATE_THEORY")
                .append("；需要异常点、research ")
                .append(GATE_RESEARCH.stripTrailingZeros().toPlainString())
                .append("、equipment ")
                .append(GATE_EQUIPMENT.stripTrailingZeros().toPlainString()).append('\n');
        sb.append("  信标：").append(state.hasTech("BEACON_THEORY") ? "已解锁" : "需要 BEACON_THEORY")
                .append("；需要玩家 3 级殖民地、material ")
                .append(BEACON_MATERIAL.stripTrailingZeros().toPlainString())
                .append("、equipment ")
                .append(BEACON_EQUIPMENT.stripTrailingZeros().toPlainString()).append('\n');
        for (String project : state.startedProjects()) {
            Ids.LocationId at = state.projectLocation(project);
            sb.append("  进行中：").append(project).append(" @ ")
                    .append(at == null ? "-" : at.id()).append("  进度 ")
                    .append(state.projectProgress(project)).append('/')
                    .append(state.projectRequiredDays(project)).append(" 天\n");
        }
        if (state.ending() != null) {
            sb.append("  ★ 结局：").append(state.ending()).append('\n');
        }
        return sb.toString();
    }

    /** 工程随附的资源需求表（诊断用）。 */
    public static Map<String, Map<ResourceKind, BigDecimal>> requirements() {
        return Map.of(
                GATE, Map.of(ResourceKind.RESEARCH, GATE_RESEARCH,
                        ResourceKind.EQUIPMENT, GATE_EQUIPMENT),
                BEACON, Map.of(ResourceKind.MATERIAL, BEACON_MATERIAL,
                        ResourceKind.EQUIPMENT, BEACON_EQUIPMENT));
    }
}
