package fc.sim;

import fc.world.Agent;
import fc.world.ColonyState;
import fc.world.Faction;
import fc.world.Ids;
import fc.world.MoneyLedger;
import fc.world.PendingDecision;
import fc.world.ResourceKind;
import fc.world.Stock;
import fc.world.SupplyLine;
import fc.world.Transfer;
import fc.world.WorkUnit;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 世界状态哈希（SHA-256）：把可观察状态按确定性顺序序列化后求摘要。
 *
 * <p>用于验收「同种子 + 同操作序列 ⇒ 同一历史」：两个世界运行到同一 Stardate 时，
 * 哈希必须相同。覆盖地点库存、主体需求/位置/进食状态、工作单元、生产中断、
 * 势力财政与声望、殖民地、在途运输、补给线、待决决策、风险/价格修正与全部账户余额。
 * 编年史属于历史记录，不属于当前状态，因此不进入哈希。</p>
 */
public final class StateHasher {

    private StateHasher() {
    }

    /** 十六进制 SHA-256 摘要。 */
    public static String hash(WorldState state) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("day=").append(state.now().day()).append('\n');

        sb.append("goods-total=");
        for (ResourceKind kind : ResourceKind.GOODS) {
            sb.append(kind.id()).append(':')
                    .append(plain(state.ledger().total(kind))).append(',');
        }
        sb.append('\n');

        List<Ids.LocationId> ids = new ArrayList<>(state.graph().nodes().keySet());
        ids.sort(Comparator.naturalOrder());
        for (Ids.LocationId id : ids) {
            Stock stock = state.stockAt(id);
            sb.append("loc=").append(id.id()).append('|');
            for (ResourceKind kind : ResourceKind.GOODS) {
                BigDecimal v = stock.amount(kind);
                if (v.signum() != 0) {
                    sb.append(kind.id()).append(':').append(plain(v)).append(',');
                }
            }
            sb.append('\n');
        }

        List<Agent> agents = new ArrayList<>(state.agents().values());
        agents.sort(Comparator.comparingLong(a -> a.id().id()));
        for (Agent a : agents) {
            sb.append("agent=").append(a.id().id()).append('|')
                    .append(a.role()).append('|').append(a.trait()).append('|')
                    .append(a.location().id()).append('|')
                    .append(a.needs().hunger()).append(',').append(a.needs().fatigue())
                    .append(',').append(a.needs().morale()).append('|')
                    .append(a.assignment()).append('|').append(a.skill()).append('|')
                    .append(a.isTraveling() ? ("T" + a.travelDestination().id()
                            + "@" + a.travelTicksRemaining()) : "-")
                    .append('|').append(a.lastAteDay()).append('|')
                    .append(a.starvingAlerted() ? "!" : "-")
                    .append('\n');
        }

        List<Map.Entry<String, WorkUnit>> units = new ArrayList<>(state.workUnits().entrySet());
        units.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, WorkUnit> e : units) {
            WorkUnit u = e.getValue();
            sb.append("work=").append(e.getKey()).append('|')
                    .append(u.recipeName()).append('|').append(u.progress())
                    .append('/').append(u.totalTicks()).append('|')
                    .append(u.at() == null ? "-" : u.at().id()).append('|');
            u.workers().stream().sorted().forEach(id ->
                    sb.append(id.id()).append(','));
            sb.append('\n');
        }

        for (Map.Entry<Ids.LocationId, fc.world.time.Stardate> e
                : state.productionDisruptions().entrySet()) {
            sb.append("disrupted=").append(e.getKey().id()).append('|')
                    .append(e.getValue().day()).append('\n');
        }

        for (Faction f : state.factions().values()) {
            sb.append("faction=").append(f.id().id()).append('|')
                    .append(plain(state.money().balance(f.account()))).append('|')
                    .append(state.reputation(f.id())).append('\n');
        }
        for (Map.Entry<String, Integer> e : state.relations().snapshot().entrySet()) {
            sb.append("relation=").append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }

        for (ColonyState c : state.colonies().values()) {
            sb.append("colony=").append(c.id().id()).append('|').append(c.level())
                    .append('|').append(c.population()).append('|').append(c.playerOwned())
                    .append('\n');
        }

        List<Transfer> transfers = new ArrayList<>(state.transfers().values());
        transfers.sort(Comparator.comparing(Transfer::id));
        for (Transfer t : transfers) {
            sb.append("transfer=").append(t.id()).append('|').append(t.state()).append('|')
                    .append(t.from().id()).append('>').append(t.to().id()).append('|')
                    .append(t.resource().id()).append(':').append(plain(t.amount()))
                    .append('|').append(t.elapsedTicks()).append('/').append(t.totalTicks())
                    .append('\n');
        }

        for (SupplyLine line : state.supplyLines().values()) {
            sb.append("supply=").append(line.id()).append('|').append(line.active()).append('|')
                    .append(line.from().id()).append('>').append(line.to().id()).append('|')
                    .append(line.resource().id()).append(':').append(plain(line.amountPerRun()))
                    .append('|').append(line.intervalDays()).append('/')
                    .append(line.travelTicks()).append('|')
                    .append(line.daysUntilNext()).append('|')
                    .append(line.deliveredRuns()).append('\n');
        }

        List<PendingDecision> decisions = new ArrayList<>(state.decisions());
        decisions.sort(Comparator.comparing(PendingDecision::id));
        for (PendingDecision d : decisions) {
            sb.append("decision=").append(d.id()).append('|').append(d.resolved())
                    .append('|').append(d.chosenOption() == null ? "-" : d.chosenOption())
                    .append('\n');
        }

        for (Map.Entry<String, WorldState.RiskModifier> e
                : new java.util.TreeMap<>(state.riskModifiers()).entrySet()) {
            sb.append("risk=").append(e.getKey()).append('|')
                    .append(plain(e.getValue().delta())).append('|')
                    .append(e.getValue().until().day()).append('\n');
        }
        for (ResourceKind kind : ResourceKind.GOODS) {
            sb.append("shock=").append(kind.id()).append('=')
                    .append(plain(state.priceShock(kind))).append('\n');
        }

        sb.append("money=");
        for (Map.Entry<String, BigDecimal> e
                : new java.util.TreeMap<>(state.money().balances()).entrySet()) {
            sb.append(e.getKey()).append('=').append(plain(e.getValue())).append(',');
        }
        sb.append('\n');
        sb.append("debt=").append(plain(state.money().debt(MoneyLedger.PLAYER))).append('\n');

        return sha256(sb.toString());
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }
}
