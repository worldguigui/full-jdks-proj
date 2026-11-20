package fc.world;

import fc.testkit.FcTest;
import fc.testkit.TestRunner;
import fc.world.Ids.LocationId;
import fc.world.time.Stardate;

import java.math.BigDecimal;
import java.util.Map;

/**
 * fc-world 核心模型测试：时间、货物守恒、财务账本、sealed 层次与事件匹配。
 */
public final class WorldSmokeTest {

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(WorldSmokeTest.class))) {
            System.exit(1);
        }
    }

    @FcTest
    public void stardateAdvances() {
        Stardate s = Stardate.EPOCH.plusDays(30);
        assert s.day() == 30;
        assert s.isAfter(Stardate.EPOCH);
        assert s.daysUntil(new Stardate(40)) == 10;
    }

    @FcTest
    public void stockArithmetic() {
        Stock s = Stock.empty().add(ResourceKind.FUEL, new BigDecimal("100"));
        assert s.amount(ResourceKind.FUEL).compareTo(new BigDecimal("100")) == 0;
        Stock s2 = s.subtract(ResourceKind.FUEL, new BigDecimal("30"));
        assert s2.amount(ResourceKind.FUEL).compareTo(new BigDecimal("70")) == 0;
        assert s.amount(ResourceKind.FUEL).compareTo(new BigDecimal("100")) == 0 : "库存不可变";
    }

    @FcTest
    public void stockUnderflowThrows() {
        Stock s = Stock.empty().add(ResourceKind.FUEL, new BigDecimal("10"));
        boolean threw = false;
        try {
            s.subtract(ResourceKind.FUEL, new BigDecimal("20"));
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assert threw : "扣减不足应抛异常";
    }

    @FcTest
    public void creditsCannotEnterPhysicalStock() {
        boolean threw = false;
        try {
            Stock.of(Map.of(ResourceKind.CREDITS, BigDecimal.TEN));
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assert threw : "信用点必须只走财务账本";
    }

    @FcTest
    public void ledgerTransferConservesTotals() {
        ResourceLedger ledger = new ResourceLedger();
        LocationId a = new LocationId(1);
        LocationId b = new LocationId(2);
        ledger.open(a, Stock.of(Map.of(ResourceKind.ORE, new BigDecimal("100"))));
        ledger.open(b, Stock.empty());

        ledger.transfer(a, b, ResourceKind.ORE, new BigDecimal("30"), new Stardate(1), "测试");

        assert ledger.total(ResourceKind.ORE).compareTo(new BigDecimal("100")) == 0;
        assert ledger.stock(a).amount(ResourceKind.ORE).compareTo(new BigDecimal("70")) == 0;
        assert ledger.stock(b).amount(ResourceKind.ORE).compareTo(new BigDecimal("30")) == 0;
    }

    @FcTest
    public void ledgerRejectsOverdraft() {
        ResourceLedger ledger = new ResourceLedger();
        LocationId a = new LocationId(1);
        LocationId b = new LocationId(2);
        ledger.open(a, Stock.of(Map.of(ResourceKind.ORE, new BigDecimal("5"))));
        ledger.open(b, Stock.empty());
        boolean threw = false;
        try {
            ledger.transfer(a, b, ResourceKind.ORE, new BigDecimal("10"), new Stardate(1), "测试");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assert threw;
        assert ledger.total(ResourceKind.ORE).compareTo(new BigDecimal("5")) == 0 : "失败操作不得改变总量";
    }

    @FcTest
    public void inTransitPartitionRemainsInWorldTotal() {
        ResourceLedger ledger = new ResourceLedger();
        LocationId a = new LocationId(1);
        LocationId b = new LocationId(2);
        ledger.open(a, Stock.of(Map.of(ResourceKind.FOOD, new BigDecimal("20"))));
        ledger.open(b, Stock.empty());

        ledger.holdForTransfer(1, a, ResourceKind.FOOD, new BigDecimal("8"), new Stardate(1), "运输");
        assert ledger.stock(a).amount(ResourceKind.FOOD).compareTo(new BigDecimal("12")) == 0;
        assert ledger.total(ResourceKind.FOOD).compareTo(new BigDecimal("20")) == 0
                : "在途货物仍是世界总量的一部分";
        assert ledger.inTransit(1).amount(ResourceKind.FOOD).compareTo(new BigDecimal("8")) == 0;

        ledger.deliverTransfer(1, b, ResourceKind.FOOD, new BigDecimal("8"), new Stardate(3), "抵达");
        assert ledger.stock(b).amount(ResourceKind.FOOD).compareTo(new BigDecimal("8")) == 0;
        assert ledger.total(ResourceKind.FOOD).compareTo(new BigDecimal("20")) == 0;
    }

    @FcTest
    public void moneyTransferConservesAndRejectsOverdraft() {
        MoneyLedger money = new MoneyLedger();
        money.open(MoneyLedger.PLAYER, new BigDecimal("100"));
        money.open(MoneyLedger.BANK, new BigDecimal("500"));

        money.transfer(new Stardate(1), MoneyLedger.PLAYER, MoneyLedger.BANK,
                new BigDecimal("30"), "测试");
        assert money.balance(MoneyLedger.PLAYER).compareTo(new BigDecimal("70")) == 0;
        assert money.total().compareTo(new BigDecimal("600")) == 0;

        boolean threw = false;
        try {
            money.transfer(new Stardate(1), MoneyLedger.PLAYER, MoneyLedger.BANK,
                    new BigDecimal("1000"), "测试");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assert threw;
        assert money.total().compareTo(new BigDecimal("600")) == 0;
    }

    @FcTest
    public void loanInterestAccruesDebt() {
        MoneyLedger money = new MoneyLedger();
        money.open(MoneyLedger.PLAYER, BigDecimal.ZERO);
        money.open(MoneyLedger.BANK, new BigDecimal("100"));
        money.borrow(new Stardate(1), MoneyLedger.PLAYER, new BigDecimal("50"), "借款");
        assert money.balance(MoneyLedger.PLAYER).compareTo(new BigDecimal("50")) == 0;
        assert money.debt(MoneyLedger.PLAYER).compareTo(new BigDecimal("50")) == 0;

        money.chargeInterest(new Stardate(2), MoneyLedger.PLAYER, new BigDecimal("0.10"));
        assert money.balance(MoneyLedger.PLAYER).compareTo(new BigDecimal("45")) == 0
                : "利息应从余额支付";
        assert money.debt(MoneyLedger.PLAYER).compareTo(new BigDecimal("50")) == 0;
    }

    @FcTest
    public void sealedLocationHierarchy() {
        Location loc = new Location.StarSystem(new LocationId(1), Stock.empty(), "Sol");
        assert loc.id().id() == 1;
        assert "Sol".equals(loc.name());
        Location updated = loc.withStock(Stock.of(Map.of(ResourceKind.FUEL, BigDecimal.ONE)));
        assert updated instanceof Location.StarSystem;
        assert updated.stock().amount(ResourceKind.FUEL).compareTo(BigDecimal.ONE) == 0;
        assert loc.stock().isEmpty() : "原地点快照不变";
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

    @FcTest
    public void relationValuesAreClamped() {
        Relations r = new Relations();
        r.adjust(new Ids.FactionId(1), new Ids.FactionId(2), 500);
        assert r.between(new Ids.FactionId(1), new Ids.FactionId(2)) == 100;
        r.adjust(new Ids.FactionId(2), new Ids.FactionId(1), -400);
        assert r.between(new Ids.FactionId(1), new Ids.FactionId(2)) == -100;
    }
}
