package fc.world;

import fc.world.Ids.FactionId;
import fc.world.Ids.LocationId;
import fc.world.time.Stardate;

import java.math.BigDecimal;

/**
 * 势力委托：在截止日期前把指定货物送到指定地点，获得报酬与声望。
 *
 * <p>委托由势力在阶段 3 生成；玩家用 {@code commission accept} 接取，
 * 把货物装上舰船后靠泊目的地交付。逾期未交付会降低声望。</p>
 */
public final class Commission {

    public enum Status {
        OFFERED, ACCEPTED, COMPLETED, FAILED
    }

    private final String id;
    private final FactionId faction;
    private final ResourceKind good;
    private final BigDecimal amount;
    private final LocationId destination;
    private final Stardate deadline;
    private final BigDecimal reward;
    private Status status = Status.OFFERED;

    public Commission(String id, FactionId faction, ResourceKind good, BigDecimal amount,
                      LocationId destination, Stardate deadline, BigDecimal reward) {
        this.id = id;
        this.faction = faction;
        this.good = good;
        this.amount = amount;
        this.destination = destination;
        this.deadline = deadline;
        this.reward = reward;
    }

    public String id() {
        return id;
    }

    public FactionId faction() {
        return faction;
    }

    public ResourceKind good() {
        return good;
    }

    public BigDecimal amount() {
        return amount;
    }

    public LocationId destination() {
        return destination;
    }

    public Stardate deadline() {
        return deadline;
    }

    public BigDecimal reward() {
        return reward;
    }

    public Status status() {
        return status;
    }

    public void accept() {
        if (status != Status.OFFERED) {
            throw new IllegalStateException("委托不可接取: " + id);
        }
        status = Status.ACCEPTED;
    }

    public void complete() {
        status = Status.COMPLETED;
    }

    public void fail() {
        status = Status.FAILED;
    }
}
