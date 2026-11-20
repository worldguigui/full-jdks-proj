package fc.runtime;

import fc.config.ConfigException;
import fc.sim.StateHasher;
import fc.testkit.FcTest;
import fc.testkit.TestRunner;
import fc.world.ResourceKind;
import fc.world.WorldState;

import java.math.BigDecimal;

/**
 * 运行时集成测试：默认世界装配、CLI 主命令、同种子重放与守恒。
 */
public final class RuntimeSmokeTest {

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(RuntimeSmokeTest.class))) {
            System.exit(1);
        }
    }

    private static RuntimeSession boot() {
        return RuntimeSession.create(new String[0]);
    }

    @FcTest
    public void defaultWorldBootsFromConfiguration() {
        RuntimeSession session = boot();
        WorldState st = session.state();
        assert st.graph().placeCount() == 16 : "静态地点数 " + st.graph().placeCount();
        assert st.graph().ships().size() == 3 : "舰船数 " + st.graph().ships().size();
        assert st.agents().size() == 36 : "主体数 " + st.agents().size();
        assert st.factions().size() == 4 : "势力数 " + st.factions().size();
        assert st.colonies().size() == 3 : "殖民地数（含玩家前哨）" + st.colonies().size();
        assert st.credits().compareTo(new BigDecimal("1000")) == 0;
        assert session.banner().size() == 7;
        st.verifyLedger();
    }

    @FcTest
    public void coreCommandsRespond() {
        RuntimeSession session = boot();
        assert !session.cli().execute("status").isBlank();
        assert !session.cli().execute("map").isBlank();
        assert !session.cli().execute("help").isBlank();
        assert !session.cli().execute("goals").isBlank();
        assert !session.cli().execute("finance").isBlank();
        assert !session.cli().execute("factions").isBlank();
        assert !session.cli().execute("ships").isBlank();
        assert !session.cli().execute("market").isBlank();
        assert session.cli().execute("no-such-command").contains("未知");
    }

    @FcTest
    public void hashCommandMatchesStateHasher() {
        RuntimeSession session = boot();
        String out = session.cli().execute("hash");
        assert out.contains(StateHasher.hash(session.state())) : out;
    }

    @FcTest
    public void nextAdvancesTimeAndKeepsLedgersConsistent() {
        RuntimeSession session = boot();
        WorldState st = session.state();
        BigDecimal food = st.ledger().total(ResourceKind.FOOD);
        BigDecimal money = st.money().total();

        session.cli().execute("next 5");

        assert st.now().day() >= 1 : "next 应推进时间";
        st.verifyLedger();
        assert st.ledger().total(ResourceKind.FOOD).signum() >= 0;
        assert food.signum() >= 0 : "食物账本快照非负";
        assert money.compareTo(BigDecimal.ZERO) >= 0;
    }

    @FcTest
    public void sameSeedAndCommandsProduceSameHash() {
        RuntimeSession a = boot();
        RuntimeSession b = boot();
        a.cli().execute("next 20");
        b.cli().execute("next 20");
        assert a.state().now().day() == b.state().now().day();
        assert StateHasher.hash(a.state()).equals(StateHasher.hash(b.state()))
                : "同种子同命令应得到相同状态哈希";
    }

    @FcTest
    public void missingWorldArgumentIsRejected() {
        try {
            RuntimeSession.create(new String[] {"--world"});
            assert false : "缺少路径应被拒绝";
        } catch (ConfigException e) {
            assert e.getMessage().contains("--world") : e.getMessage();
        }
    }

    @FcTest
    public void missingWorldFileIsRejected() {
        try {
            RuntimeSession.create(new String[] {"--world", "no-such-world.xml"});
            assert false : "缺失文件应被拒绝";
        } catch (ConfigException e) {
            assert e.getMessage().contains("找不到世界配置文件") : e.getMessage();
        }
    }
}
