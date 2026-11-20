package fc.sim.tick;

import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Location;
import fc.world.SupplyLine;
import fc.world.Transfer;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 阶段 7：航行与物流。
 *
 * <p>顺序：舰船在航推进与抵达 → 在途货物运输抵达结算 → 到期的补给线发车。
 * 补给线只描述固定间隔的调运计划，实际货物仍走 {@link Transfer} 的在途分区，
 * 因此跨地点运输前后世界总量守恒。</p>
 */
public final class VoyageStage implements TickStage {

    @Override
    public String name() {
        return "7-voyage";
    }

    @Override
    public void apply(WorldState state, TickContext ctx) {
        List<Location.Ship> ships = new ArrayList<>(state.graph().ships());
        ships.sort(Comparator.comparingLong(s -> s.shipId().id()));
        for (Location.Ship ship : ships) {
            if (!ship.inVoyage()) {
                continue;
            }
            Ids.LocationId from = ship.voyage().from();
            boolean arrived = ship.advanceVoyage();
            if (arrived) {
                ship.arrive();
                ctx.events().enqueue(new GameEvent.ShipArrived(
                        state.now(), ship.parent(), ship.shipId(), from));
            }
        }

        for (Transfer t : state.advanceTransfers()) {
            ctx.events().enqueue(new GameEvent.TransferArrived(
                    state.now(), t.to(), t.id(), t.from(), t.resource(), t.amount()));
            ctx.report("运输抵达 " + t.id() + "：" + t.amount().stripTrailingZeros().toPlainString()
                    + " " + t.resource().id() + " → " + t.to().id());
        }

        for (SupplyLine line : state.supplyLines().values()) {
            if (!line.due()) {
                continue;
            }
            BigDecimal available = state.goods(line.from(), line.resource());
            if (available.compareTo(line.amountPerRun()) < 0) {
                ctx.report("补给线 " + line.id() + " 货源不足（需 "
                        + line.amountPerRun().stripTrailingZeros().toPlainString()
                        + "，仅有 " + available.stripTrailingZeros().toPlainString() + "），本批跳过");
                continue;
            }
            Transfer t = state.startTransfer(line.from(), line.to(), line.resource(),
                    line.amountPerRun(), line.travelTicks(), "补给线 " + line.id());
            line.countDelivery();
            ctx.events().enqueue(new GameEvent.TransferStarted(
                    state.now(), line.to(), t.id(), t.from(), t.to(), t.resource(),
                    t.amount(), t.totalTicks()));
        }
    }
}
