package fc.world;

import fc.world.Ids.LocationId;
import fc.world.Ids.TransferId;
import fc.world.time.Stardate;

import java.math.BigDecimal;

/**
 * 跨地点货物运输：沿 {@link Link} 移动，抵达时结算。
 *
 * <p>货物在运输期间位于账本的「在途分区」，因此世界总量守恒。
 * 人员迁徙沿用统一 {@link Agent} 移动规则，不由本类承载。</p>
 */
public final class Transfer {

    public enum State {
        IN_TRANSIT, DELIVERED, CANCELLED
    }

    private final TransferId id;
    private final LocationId from;
    private final LocationId to;
    private final ResourceKind resource;
    private final BigDecimal amount;
    private int totalTicks;
    private final Stardate departed;
    private final String reason;
    private int elapsedTicks;
    private State state;

    public Transfer(TransferId id, LocationId from, LocationId to, ResourceKind resource,
                    BigDecimal amount, int totalTicks, Stardate departed, String reason) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("运输数量必须为正: " + amount);
        }
        if (totalTicks < 1) {
            throw new IllegalArgumentException("运输天数必须 ≥ 1: " + totalTicks);
        }
        this.id = id;
        this.from = from;
        this.to = to;
        this.resource = resource;
        this.amount = amount;
        this.totalTicks = totalTicks;
        this.departed = departed;
        this.reason = reason == null ? "" : reason;
        this.state = State.IN_TRANSIT;
    }

    public TransferId id() {
        return id;
    }

    public LocationId from() {
        return from;
    }

    public LocationId to() {
        return to;
    }

    public ResourceKind resource() {
        return resource;
    }

    public BigDecimal amount() {
        return amount;
    }

    public int totalTicks() {
        return totalTicks;
    }

    public int elapsedTicks() {
        return elapsedTicks;
    }

    public Stardate departed() {
        return departed;
    }

    public String reason() {
        return reason;
    }

    public State state() {
        return state;
    }

    public boolean arrived() {
        return elapsedTicks >= totalTicks;
    }

    /** 延误（风暴等）：延长总行程，而不是回退已走的天数。 */
    public void delay(int extraTicks) {
        if (extraTicks > 0 && state == State.IN_TRANSIT) {
            totalTicks += extraTicks;
        }
    }

    public void advance() {
        if (state == State.IN_TRANSIT) {
            elapsedTicks++;
        }
    }

    public void deliver() {
        state = State.DELIVERED;
    }

    public void cancel() {
        state = State.CANCELLED;
    }
}
