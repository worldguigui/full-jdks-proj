package fc.runtime;

import fc.colony.ProjectService;
import fc.science.TechTree;
import fc.testkit.FcTest;
import fc.testkit.TestRunner;
import fc.world.Agent;
import fc.world.Assignment;
import fc.world.Commission;
import fc.world.Ids;
import fc.world.KnowledgeState;
import fc.world.Location;
import fc.world.MoneyLedger;
import fc.world.PendingDecision;
import fc.world.ResourceKind;
import fc.world.Treaty;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * M4 游戏性集成测试：驾驶默认世界走通科技、测绘、维修、船坞、外交与终局。
 *
 * <p>所有操作都经由 CLI 或公开服务完成，不绕过 tick 管线；测试中的
 * {@code state.produce} 只用于准备「远征已运抵的物资」这类前置条件。
 * 随机事件产生的待决决策由 {@link #step(RuntimeSession)} 自动选择低风险选项，
 * 这样长时间推进不会卡在「等待玩家决策」。</p>
 */
public final class M4GameplayTest {

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(M4GameplayTest.class))) {
            System.exit(1);
        }
    }

    private static RuntimeSession boot() {
        return RuntimeSession.create(new String[0]);
    }

    private static Location.Ship ship(WorldState state, long id) {
        return state.graph().ships().stream()
                .filter(s -> s.shipId().id() == id)
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到舰船 #" + id));
    }

    private static Ids.LocationId loc(long id) {
        return new Ids.LocationId(id);
    }

    private static Ids.AgentId agent(long id) {
        return new Ids.AgentId(id);
    }

    /** 推进一天；若出现待决决策，先选一个不会因资金不足而失败的选项。 */
    private static void step(RuntimeSession session) {
        WorldState state = session.state();
        while (state.hasPendingDecision()) {
            PendingDecision d = state.pendingDecisions().get(0);
            PendingDecision.Option pick = d.options().stream()
                    .filter(o -> o.effect() == PendingDecision.Effect.ACCEPT)
                    .findFirst()
                    .or(() -> d.options().stream()
                            .filter(o -> o.effect() == PendingDecision.Effect.DECLINE)
                            .findFirst())
                    .or(() -> d.options().stream()
                            .filter(o -> o.effect() == PendingDecision.Effect.REFUSE_TRIBUTE)
                            .findFirst())
                    .orElse(d.options().get(0));
            session.cli().execute("decide " + d.id().id() + " " + pick.id());
        }
        session.cli().execute("next 1");
    }

    private static void advance(RuntimeSession session, int days) {
        for (int i = 0; i < days; i++) {
            step(session);
        }
    }

    // ------------------------------------------------------------ 默认世界

    @FcTest
    public void defaultWorldExposesM4Systems() {
        RuntimeSession session = boot();
        WorldState st = session.state();

        Location.Ship freighter = ship(st, 1);
        assert "freighter".equals(freighter.shipType()) : freighter.shipType();
        assert freighter.cargoCapacity().compareTo(new BigDecimal("320")) == 0;
        assert freighter.crewCapacity() == 6;
        assert freighter.maxHull() == 100;

        Location.Ship engineering = ship(st, 3);
        assert "engineering".equals(engineering.shipType());
        assert engineering.maxHull() == 120 : "工程船耐久上限";
        assert engineering.hull() == 70 : "拓荒号应以受损状态开局";
        assert engineering.cargoCapacity().compareTo(new BigDecimal("180")) == 0;

        assert "SHIPYARD".equals(st.facilityKind(loc(11)));
        assert st.workerCapacity(loc(11)) == 4;
        assert "LAB".equals(st.facilityKind(loc(12)));
        assert st.workerCapacity(loc(12)) == 2;

        assert st.knowledgeOf(loc(6)) == KnowledgeState.DETECTED;
        assert st.knowledgeOf(loc(12)) == KnowledgeState.UNKNOWN;
        assert st.agentsAt(loc(12)).size() == 1;

        Agent researcher = st.agents().get(agent(34));
        assert researcher.skills().research() >= 7 : "实验室驻员科研技能";
        assert st.agentsAt(ship(st, 3).id()).stream()
                .anyMatch(a -> a.skills().engineering() >= 3) : "拓荒号应有维修船员";

        String goals = session.cli().execute("goals");
        for (String stage : List.of("D0", "D1", "D2", "D3", "D4")) {
            assert goals.contains(stage) : "目标面板缺少 " + stage;
        }
        assert goals.contains("已知地点");
        for (String route : List.of("商业霸权", "外交联盟", "星域测绘")) {
            assert goals.contains(route) : "胜利路线面板缺少 " + route;
        }
        for (String failure : List.of("船员危机", "债务违约", "声望崩溃")) {
            assert goals.contains(failure) : "失败条件面板缺少 " + failure;
        }
        assert session.cli().execute("research").contains("测绘无人机");
        assert session.cli().execute("project").contains("星门");
        assert session.cli().execute("order ships").contains("科考船");
        assert session.cli().execute("diplomacy").contains("边境舰队");
        st.verifyLedger();
    }

    // ------------------------------------------------------------ 发现与测绘

    @FcTest
    public void scanAndSurveyRevealTheHiddenLab() {
        RuntimeSession session = boot();
        WorldState st = session.state();
        Location.Ship engineering = ship(st, 3);

        String sail = session.cli().execute("sail 3 6");
        assert sail.startsWith("启航") : sail;
        for (int i = 0; i < 16 && engineering.inVoyage(); i++) {
            step(session);
        }
        assert !engineering.inVoyage() : "拓荒号应在 16 天内抵达猎户之门";
        assert engineering.parent().equals(loc(6)) : engineering.parent().id();
        assert st.knowledgeOf(loc(12)) == KnowledgeState.UNKNOWN
                : "开普勒带在扫描前应为未知";

        String scan = session.cli().execute("scan 3");
        assert scan.contains("新增 1") : scan;
        assert st.knowledgeOf(loc(12)) == KnowledgeState.DETECTED
                : "扫描应揭示相邻的未知实验室";

        BigDecimal researchBefore = st.goods(engineering.id(), ResourceKind.RESEARCH);
        String survey = session.cli().execute("survey start 3 6");
        assert survey.startsWith("开始测绘") : survey;
        assert st.surveyAt(loc(6)) != null;
        for (int i = 0; i < 10 && st.surveyAt(loc(6)) != null; i++) {
            step(session);
        }
        assert st.surveyAt(loc(6)) == null : "测绘应在 10 天内完成";
        assert st.knowledgeOf(loc(6)) == KnowledgeState.CHARTED;
        assert st.goods(engineering.id(), ResourceKind.RESEARCH)
                .compareTo(researchBefore) > 0 : "测绘应产出科研数据";
        Agent surveyor = st.agents().get(agent(5));
        assert surveyor.assignment() == Assignment.WORKING
                : "测绘结束后船员应回到工作，而不是永久停留在 SURVEYING";
        st.verifyLedger();
    }

    // ------------------------------------------------------------ 科研

    @FcTest
    public void researchUnlocksTechAndReleasesTheLabCrew() {
        RuntimeSession session = boot();
        WorldState st = session.state();

        String start = session.cli().execute("research start SURVEY_DRONE");
        assert start.startsWith("立项") : start;
        assert st.isResearching(TechTree.SURVEY_DRONE);
        assert st.agents().get(agent(34)).assignment() == Assignment.RESEARCH;

        for (int i = 0; i < 10 && !st.hasTech(TechTree.SURVEY_DRONE); i++) {
            step(session);
        }
        assert st.hasTech(TechTree.SURVEY_DRONE) : "科研应在 10 天内解锁测绘无人机";
        assert !st.isResearching(TechTree.SURVEY_DRONE);
        assert st.agents().get(agent(34)).assignment() == Assignment.WORKING
                : "完工后研究员应回到普通工作";
        assert st.goods(loc(12), ResourceKind.RESEARCH)
                .compareTo(new BigDecimal("2")) == 0 : "立项消耗 2 单位科研数据";
        assert session.cli().execute("research status").contains("SURVEY_DRONE")
                || session.cli().execute("research").contains("已解锁");
        st.verifyLedger();
    }

    // ------------------------------------------------------------ 维修与贷款

    @FcTest
    public void repairConsumesMaterialAndCredits() {
        RuntimeSession session = boot();
        WorldState st = session.state();
        Location.Ship engineering = ship(st, 3);
        BigDecimal credits = st.credits();
        BigDecimal material = st.goods(loc(7), ResourceKind.MATERIAL);

        String out = session.cli().execute("repair 3");
        assert out.startsWith("维修 拓荒号") : out;
        assert engineering.hull() > 70 : "维修应恢复耐久";
        assert engineering.hull() <= engineering.maxHull();
        assert st.credits().compareTo(credits) < 0 : "维修应支付信用点";
        assert st.goods(loc(7), ResourceKind.MATERIAL).compareTo(material) < 0
                : "维修应消耗材料";
        st.verifyLedger();
    }

    @FcTest
    public void bankLoansKeepMoneyLedgerBalanced() {
        RuntimeSession session = boot();
        WorldState st = session.state();
        BigDecimal totalBefore = st.money().total();

        String borrow = session.cli().execute("borrow 500");
        assert borrow.startsWith("借款 500") : borrow;
        assert st.money().debt(MoneyLedger.PLAYER).compareTo(new BigDecimal("500")) == 0;
        assert st.credits().compareTo(new BigDecimal("1500")) == 0;

        String repay = session.cli().execute("repay 200");
        assert repay.startsWith("还款 200") : repay;
        assert st.money().debt(MoneyLedger.PLAYER).compareTo(new BigDecimal("300")) == 0;
        assert st.credits().compareTo(new BigDecimal("1300")) == 0;

        assert session.cli().execute("repay 5000").contains("超过债务");
        assert session.cli().execute("borrow 9000").contains("超过银行余额");
        assert st.money().total().compareTo(totalBefore) == 0
                : "借款与还款只在账户间转移，资金总量不变";
        assert st.money().debt(MoneyLedger.PLAYER).compareTo(new BigDecimal("300")) == 0;
        st.verifyLedger();
    }

    // ------------------------------------------------------------ 船坞订单

    @FcTest
    public void shipyardBuildsANewResearchShip() {
        RuntimeSession session = boot();
        WorldState st = session.state();
        assert st.credits().compareTo(new BigDecimal("1000")) == 0;

        String order = session.cli().execute("order ship research 曙光号");
        assert order.startsWith("船坞订单") : order;
        assert st.shipOrders().size() == 1;
        assert st.shipOrders().get(0).remainingDays() == 7 : "科考船工期应为 7 天";
        assert st.credits().compareTo(new BigDecimal("50")) == 0 : "科考船造价 950";

        for (int i = 0; i < 10 && st.graph().ships().size() == 3; i++) {
            step(session);
        }
        assert st.graph().ships().size() == 4 : "订单完成后应多一艘舰船";
        assert st.shipOrders().get(0).status() == fc.world.ShipOrder.Status.DELIVERED;
        Location.Ship built = st.graph().ships().stream()
                .max(Comparator.comparingLong(s -> s.shipId().id())).orElseThrow();
        assert "research".equals(built.shipType());
        assert "曙光号".equals(built.name());
        assert built.parent().equals(loc(11)) : "新船应停靠在船坞";
        assert built.maxHull() == 70;
        st.verifyLedger();
    }

    // ------------------------------------------------------------ 外交

    @FcTest
    public void giftAndTreatyAtFactionSeat() {
        RuntimeSession session = boot();
        WorldState st = session.state();
        Ids.FactionId fleetFaction = new Ids.FactionId(3);

        String gift = session.cli().execute("gift 3 100");
        assert gift.startsWith("向 边境舰队 赠礼") : gift;
        assert st.reputation(fleetFaction) == 1 : "100 信用点应换取 1 点声望";
        assert st.credits().compareTo(new BigDecimal("900")) == 0;

        st.adjustReputation(fleetFaction, 19);
        String treaty = session.cli().execute("treaty 3 trade");
        assert treaty.startsWith("与 边境舰队 签署贸易协定") : treaty;
        assert st.hasTreaty(fleetFaction, Treaty.TRADE);
        assert st.treaties().get(fleetFaction).contains(Treaty.TRADE);
        assert st.credits().compareTo(new BigDecimal("700")) == 0 : "贸易协定费用 200";

        String diplomacy = session.cli().execute("diplomacy");
        assert diplomacy.contains("边境舰队") && diplomacy.contains("贸易协定") : diplomacy;
        st.verifyLedger();
    }

    @FcTest
    public void commissionIsOfferedAcceptedAndDelivered() {
        RuntimeSession session = boot();
        WorldState st = session.state();
        advance(session, 4);

        Commission target = st.commissions().values().stream()
                .filter(c -> c.status() == Commission.Status.OFFERED)
                .filter(c -> c.faction().id() == 4)
                .findFirst()
                .orElseThrow(() -> new AssertionError("观测院应在第 4 天发布科研委托"));
        assert target.good() == ResourceKind.RESEARCH;
        assert target.destination().equals(loc(6));

        String accept = session.cli().execute("commission accept " + target.id());
        assert accept.startsWith("已接取委托 " + target.id()) : accept;
        assert target.status() == Commission.Status.ACCEPTED;

        Location.Ship freighter = ship(st, 1);
        st.produce(freighter.id(), ResourceKind.RESEARCH, target.amount(), "测试：备妥委托货物");
        String sail = session.cli().execute("sail 1 6");
        assert sail.startsWith("启航") : sail;
        for (int i = 0; i < 30 && freighter.inVoyage(); i++) {
            step(session);
        }
        assert !freighter.inVoyage() : "晨星号应在 30 天内抵达观测院，ending=" + st.ending();
        assert freighter.parent().equals(loc(6));

        BigDecimal creditsBefore = st.credits();
        String deliver = session.cli().execute("commission deliver " + target.id() + " 1");
        assert deliver.startsWith("委托完成 " + target.id()) : deliver;
        assert target.status() == Commission.Status.COMPLETED;
        assert st.credits().compareTo(creditsBefore) > 0 : "交付应获得报酬";
        st.verifyLedger();
    }

    // ------------------------------------------------------------ 终局

    @FcTest
    public void gateProjectReachesTheEndingAndFreezesTime() {
        RuntimeSession session = boot();
        WorldState st = session.state();
        Ids.LocationId anomaly = loc(6);

        st.produce(anomaly, ResourceKind.RESEARCH, new BigDecimal("2"), "测试：补充科研数据");
        st.produce(anomaly, ResourceKind.EQUIPMENT, new BigDecimal("8"), "测试：运抵工程设备");
        st.produce(anomaly, ResourceKind.FOOD, new BigDecimal("60"), "测试：工程队口粮");
        st.unlockTech(TechTree.GATE_THEORY);
        st.agents().get(agent(5)).moveTo(anomaly);

        assert st.goods(anomaly, ResourceKind.RESEARCH)
                .compareTo(ProjectService.GATE_RESEARCH) == 0;
        assert st.goods(anomaly, ResourceKind.EQUIPMENT)
                .compareTo(ProjectService.GATE_EQUIPMENT) == 0;

        String start = session.cli().execute("project gate");
        assert start.startsWith("星门工程启动") : start;
        assert st.projectStarted(ProjectService.GATE);
        assert st.projectLocation(ProjectService.GATE).equals(anomaly);

        for (int i = 0; i < 16 && st.ending() == null; i++) {
            step(session);
        }
        assert "星门启航".equals(st.ending()) : "星门应在 16 天内完工并进入结局，ending=" + st.ending();
        assert st.projectCompleted(ProjectService.GATE);
        assert st.projectProgress(ProjectService.GATE) == ProjectService.GATE_DAYS;

        long frozenDay = st.now().day();
        session.cli().execute("next 3");
        assert st.now().day() == frozenDay : "结局后时间应冻结";
        assert session.cli().execute("goals").contains("终局已达成");
        assert session.cli().execute("project").contains("星门启航");
        st.verifyLedger();
    }
}
