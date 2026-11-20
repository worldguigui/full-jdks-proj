package fc.world;

import fc.testkit.FcTest;
import fc.testkit.TestRunner;
import fc.world.Ids.LocationId;
import fc.world.time.Stardate;

import java.math.BigDecimal;

/**
 * fc-world 核心模型的 M0 冒烟测试。
 */
public final class WorldSmokeTest {

    public static void main(String[] args) {
        TestRunner.report(TestRunner.run(WorldSmokeTest.class));
    }

    @FcTest
    public void stardateAdvances() {
        Stardate s = Stardate.EPOCH.plusDays(30);
        assert s.day() == 30;
        assert s.isAfter(Stardate.EPOCH);
        assert s.daysUntil(new Stardate(40)) == 10;
    }

    @FcTest
    public void stockConservation() {
        Stock s = Stock.empty().add("fuel", new BigDecimal("100"));
        assert s.amount("fuel").compareTo(new BigDecimal("100")) == 0;
        Stock s2 = s.subtract("fuel", new BigDecimal("30"));
        assert s2.amount("fuel").compareTo(new BigDecimal("70")) == 0;
    }

    @FcTest
    public void stockUnderflowThrows() {
        Stock s = Stock.empty().add("fuel", new BigDecimal("10"));
        boolean threw = false;
        try {
            s.subtract("fuel", new BigDecimal("20"));
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assert threw : "扣减不足应抛异常";
    }

    @FcTest
    public void ledgerDebitThrowsWhenInsufficient() {
        ResourceLedger ledger = new ResourceLedger();
        ledger.credit("ore", new BigDecimal("5"));
        boolean threw = false;
        try {
            ledger.debit("ore", new BigDecimal("10"));
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assert threw;
    }

    @FcTest
    public void sealedLocationHierarchy() {
        Location loc = new Location.StarSystem(new LocationId(1), Stock.empty(), "Sol");
        assert loc.id().id() == 1;
        assert "Sol".equals(loc.name());
        // sealed 层次：Location 是 sealed 根，StarSystem 是其 permitted 记录
        assert loc instanceof Location.StarSystem;
    }

    @FcTest
    public void eventPatternMatch() {
        GameEvent e = new GameEvent.IonStorm(new Stardate(5), new LocationId(3), 7);
        String rendered = switch (e) {
            case GameEvent.IonStorm(var d, var loc, var s) when s > 3 -> "风暴 " + s;
            default -> "其他";
        };
        assert "风暴 7".equals(rendered) : rendered;
    }
}
