package fc.fleet;

import fc.world.Agent;
import fc.world.Assignment;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Location;
import fc.world.WorldState;

/**
 * 船员调动服务：在同一停泊点的舰船与设施之间移动主体。
 *
 * <p>舰船是 {@link Location}，因此船员「在舰上」等价于其位置是舰船 id。
 * 调动只允许发生在同一停泊点（同一父地点）内；进入舰船受 {@code crewCapacity} 限制，
 * 进入静态设施受 {@code workerCapacity} 限制。移动后主体回到工作指派。</p>
 */
public final class CrewService {

    private CrewService() {
    }

    public record Result(boolean ok, String message) {
    }

    /** 位置所属的停泊点：舰船返回其父地点，静态地点返回自身。 */
    public static Ids.LocationId dockOf(WorldState state, Ids.LocationId id) {
        Location loc = state.graph().get(id);
        if (loc instanceof Location.Ship ship) {
            return ship.parent();
        }
        return id;
    }

    /** 把主体调到目标地点/舰船。 */
    public static Result assign(WorldState state, Ids.AgentId agentId, Ids.LocationId target) {
        Agent agent = state.agents().get(agentId);
        if (agent == null) {
            return new Result(false, "主体不存在: " + agentId.id());
        }
        if (agent.isTraveling()) {
            return new Result(false, "主体在旅途中，无法调动");
        }
        Location to = state.graph().get(target);
        if (to == null) {
            return new Result(false, "目标地点不存在: " + target.id());
        }
        Ids.LocationId fromDock = dockOf(state, agent.location());
        Ids.LocationId toDock = dockOf(state, target);
        if (!fromDock.equals(toDock)) {
            return new Result(false, "只能在同一停泊点内调动（当前 "
                    + fromDock.id() + "，目标 " + toDock.id() + "）");
        }
        if (agent.location().equals(target)) {
            return new Result(false, agent.name() + " 已在该位置");
        }
        if (to instanceof Location.Ship ship) {
            if (ship.inVoyage()) {
                return new Result(false, "舰船在航中，无法登舰");
            }
            int aboard = state.agentsAt(ship.id()).size();
            if (aboard >= ship.crewCapacity()) {
                return new Result(false, ship.name() + " 船员铺位已满（"
                        + aboard + "/" + ship.crewCapacity() + "）");
            }
        } else {
            int capacity = state.workerCapacity(target);
            if (capacity > 0 && state.agentsAt(target).size() >= capacity) {
                return new Result(false, to.name() + " 工位已满（"
                        + state.agentsAt(target).size() + "/" + capacity + "）");
            }
        }
        Ids.LocationId from = agent.location();
        agent.moveTo(target);
        if (agent.assignment() != Assignment.TRAVELING) {
            agent.assign(Assignment.WORKING);
        }
        state.chronicle().record(new GameEvent.CrewMigrated(
                state.now(), from, agentId, target, "船员调动"));
        return new Result(true, agent.name() + " 已从 " + nameOf(state, from)
                + " 调往 " + nameOf(state, target));
    }

    private static String nameOf(WorldState state, Ids.LocationId id) {
        Location loc = state.graph().get(id);
        return loc == null ? ("#" + id.id()) : loc.name();
    }
}
