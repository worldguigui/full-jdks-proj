package fc.sim.tick;

import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Location;
import fc.world.WorldState;

/**
 * 阶段 7：航行与物流（在航推进、抵达处理）。
 */
public final class VoyageStage implements TickStage {

    @Override
    public String name() {
        return "7-voyage";
    }

    @Override
    public void apply(WorldState state, TickContext ctx) {
        for (Location.Ship ship : state.graph().ships()) {
            if (!ship.inVoyage()) {
                continue;
            }
            Ids.LocationId from = ship.parent();
            boolean arrived = ship.advanceVoyage();
            if (arrived) {
                ship.arrive();
                ctx.events().enqueue(new GameEvent.ShipArrived(
                        state.now(), ship.parent(), ship.shipId(), from));
            }
        }
    }
}
