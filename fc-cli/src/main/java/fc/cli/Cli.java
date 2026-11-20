package fc.cli;

import fc.colony.ColonyService;
import fc.colony.LogisticsService;
import fc.economy.TradeService;
import fc.fleet.VoyageService;
import fc.sim.DecisionResolver;
import fc.sim.GameLoop;
import fc.sim.StateHasher;
import fc.world.Agent;
import fc.world.ColonyState;
import fc.world.Faction;
import fc.world.Ids;
import fc.world.Location;
import fc.world.MoneyLedger;
import fc.world.PendingDecision;
import fc.world.ResourceKind;
import fc.world.SupplyLine;
import fc.world.Transfer;
import fc.world.WorldState;
import fc.world.time.Stardate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * CLI 命令壳。
 *
 * <p>命令的唯一清单是 {@link #COMMANDS}：分发与 {@code help} 都读它，
 * 因此新增命令只需改一处，不会出现「有命令但 help 里没写」的情况。</p>
 */
public final class Cli {

    /** 一条命令的定义：主名 + 别名 + 用法 + 说明。 */
    private record Cmd(String name, List<String> aliases, String usage, String desc) {
    }

    /** 全部命令（唯一事实来源）。 */
    private static final List<Cmd> COMMANDS = List.of(
            new Cmd("map", List.of(), "map",
                    "ASCII 星图：地点、库存、航线、舰队位置"),
            new Cmd("ships", List.of(), "ships",
                    "舰队列表：位置、燃料、在航进度"),
            new Cmd("crew", List.of(), "crew",
                    "主体名册：角色、位置、需求、指派与行程"),
            new Cmd("sail", List.of(), "sail <舰船> <目标地点>",
                    "启航（舰船与目标都可用 id 或名字）"),
            new Cmd("market", List.of(), "market [舰船]",
                    "当前地点行情：库存、买入价、卖出价"),
            new Cmd("trade", List.of(), "trade buy|sell <资源> <数量> [舰船]",
                    "按行情买卖；quote <资源> 只看报价"),
            new Cmd("next", List.of(), "next <天数>",
                    "推进世界时间（有待决决策时会提前停止）"),
            new Cmd("factions", List.of(), "factions",
                    "势力：中心、优先货物、库存、财政与声望"),
            new Cmd("decisions", List.of("decision"), "decisions",
                    "待决决策列表与可选方案"),
            new Cmd("decide", List.of(), "decide <决策id> <选项id>",
                    "做出选择并应用后果"),
            new Cmd("colonies", List.of("colony"), "colonies [地点]",
                    "殖民地列表；给地点则显示升级要求"),
            new Cmd("upgrade", List.of(), "upgrade <地点>",
                    "用货物与信用点把玩家殖民地升一级"),
            new Cmd("supply", List.of(), "supply list|add|cancel ...",
                    "补给线：list / add <id> <起点> <终点> <资源> <每批> <间隔> / cancel <id>"),
            new Cmd("goals", List.of(), "goals",
                    "阶段目标进度（D0 生存期 / D1 立足期）"),
            new Cmd("finance", List.of(), "finance",
                    "财务：账户余额、贷款与最近流水"),
            new Cmd("hash", List.of(), "hash",
                    "当前世界状态哈希（确定性回放校验）"),
            new Cmd("trace", List.of(), "trace [条数]",
                    "追踪风暴因果链与重大事件"),
            new Cmd("log", List.of("chronicle"), "log [条数|all]",
                    "查看编年史（默认最近 8 条；all 看全部）"),
            new Cmd("status", List.of(), "status",
                    "世界概况：时间、地点数、舰船数、事件数"),
            new Cmd("help", List.of(), "help",
                    "显示全部命令与本说明"),
            new Cmd("quit", List.of("exit"), "quit",
                    "退出游戏（exit 同义）")
    );

    private final GameLoop loop;
    private final TradeService trade;

    public Cli(GameLoop loop) {
        this(loop, null);
    }

    public Cli(GameLoop loop, TradeService trade) {
        this.loop = loop;
        this.trade = trade;
    }

    /** 把输入命令名/别名解析为规范名（未知则原样返回）。 */
    private static String canonical(String name) {
        for (Cmd c : COMMANDS) {
            if (c.name().equals(name) || c.aliases().contains(name)) {
                return c.name();
            }
        }
        return name;
    }

    /**
     * 执行单条命令，返回输出文本。
     */
    public String execute(String line) {
        if (line == null || line.isBlank()) {
            return "";
        }
        List<String> tokens = TokenScanner.tokenize(line);
        String cmd = canonical(tokens.get(0));
        return switch (cmd) {
            case "next" -> doNext(tokens);
            case "status" -> status();
            case "map" -> StarMapRenderer.render(loop.state());
            case "ships" -> ships();
            case "crew" -> crew();
            case "sail" -> doSail(tokens);
            case "market" -> doMarket(tokens);
            case "trade" -> doTrade(tokens);
            case "factions" -> factions();
            case "decisions" -> decisions();
            case "decide" -> doDecide(tokens);
            case "colonies" -> doColonies(tokens);
            case "upgrade" -> doUpgrade(tokens);
            case "supply" -> doSupply(tokens);
            case "goals" -> goals();
            case "finance" -> finance();
            case "hash" -> StateHasher.hash(loop.state());
            case "trace" -> doTrace(tokens);
            case "log" -> doChronicle(tokens);
            case "help" -> help();
            case "quit" -> "bye";
            default -> "未知命令: " + tokens.get(0) + "（输入 help 查看全部 " + COMMANDS.size() + " 条命令）";
        };
    }

    /** 查看最近事件（默认 8 条；更早的折叠并提示）。命令名 log（chronicle 同义）。 */
    private String doChronicle(List<String> tokens) {
        int limit = parseLimit(tokens, 8);
        var all = loop.state().chronicle().all();
        if (all.isEmpty()) {
            return "编年史暂无事件。用 next 推进时间，或 sail 启航。";
        }
        int from = Math.max(0, all.size() - limit);
        StringBuilder sb = new StringBuilder("---- 编年史 ").append(all.size()).append(" 条");
        if (from > 0) {
            sb.append("（显示最近 ").append(all.size() - from).append("）");
        }
        sb.append(" ----\n");
        for (int i = from; i < all.size(); i++) {
            sb.append("  ").append(renderEvent(all.get(i))).append('\n');
        }
        if (from > 0) {
            sb.append("  … 更早 ").append(from).append(" 条（log all 查看全部）\n");
        }
        return sb.toString();
    }

    private int parseLimit(List<String> tokens, int dflt) {
        if (tokens.size() < 2) {
            return dflt;
        }
        if ("all".equalsIgnoreCase(tokens.get(1))) {
            return Integer.MAX_VALUE;
        }
        try {
            return Math.max(1, Integer.parseInt(tokens.get(1)));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** 事件渲染：sealed + 模式匹配（含 ExtensionEvent 扩展口，故保留 default）。 */
    private String renderEvent(fc.world.GameEvent e) {
        String head = "[" + e.when() + "] ";
        return switch (e) {
            case fc.world.GameEvent.ShipDeparted d ->
                    head + "启航 舰船#" + d.ship().id() + " " + nameOf(d.from()) + " → " + nameOf(d.to())
                            + "（" + d.totalTicks() + " 天）";
            case fc.world.GameEvent.ShipArrived a ->
                    head + "抵达 舰船#" + a.ship().id() + " 靠泊 " + nameOf(a.where());
            case fc.world.GameEvent.IonStorm s ->
                    head + "离子风暴 强度 " + s.severity() + " 于 " + nameOf(s.where());
            case fc.world.GameEvent.StormImpact s ->
                    head + "风暴影响 " + nameOf(s.where()) + "（第 " + s.step() + " 跳）"
                            + (s.effect().isEmpty() ? "" : "：" + s.effect());
            case fc.world.GameEvent.CrewAte x ->
                    head + "进食 " + agentName(x.actor()) + "（饥饿 " + x.hungerBefore() + "）于 "
                            + nameOf(x.where());
            case fc.world.GameEvent.CrewMigrated x ->
                    head + "迁徙 " + agentName(x.actor()) + " " + nameOf(x.where())
                            + " → " + nameOf(x.to()) + "（" + x.reason() + "）";
            case fc.world.GameEvent.CrewArrived x ->
                    head + "抵达 " + agentName(x.actor()) + " 于 " + nameOf(x.where())
                            + "（自 " + nameOf(x.from()) + "）";
            case fc.world.GameEvent.CrewRefusedDuty x ->
                    head + "拒绝指令 " + agentName(x.actor()) + "：" + x.reason();
            case fc.world.GameEvent.CrewStarving x ->
                    head + "饥饿告急 " + agentName(x.actor()) + "（" + x.hunger() + "）于 "
                            + nameOf(x.where());
            case fc.world.GameEvent.Wedding w ->
                    head + "婚礼 于 " + nameOf(w.where());
            case fc.world.GameEvent.FactionAction f ->
                    head + nameOf(f.where()) + " 势力行动：" + f.goal() + "｜" + f.detail();
            case fc.world.GameEvent.DecisionRaised d ->
                    head + "待决决策 " + d.decision() + "：" + d.title() + "（用 decisions 查看）";
            case fc.world.GameEvent.DecisionResolved d ->
                    head + "决策 " + d.decision() + "：" + d.outcome();
            case fc.world.GameEvent.TransferStarted t ->
                    head + "运输出发 " + t.transfer() + " " + t.amount().stripTrailingZeros().toPlainString()
                            + " " + t.resource().id() + " " + nameOf(t.from()) + " → " + nameOf(t.to())
                            + "（" + t.ticks() + " 天）";
            case fc.world.GameEvent.TransferArrived t ->
                    head + "运输抵达 " + t.transfer() + " " + t.amount().stripTrailingZeros().toPlainString()
                            + " " + t.resource().id() + " 于 " + nameOf(t.where());
            case fc.world.GameEvent.ColonyUpgraded c ->
                    head + "殖民地升级 " + nameOf(c.where()) + " → " + c.level() + " 级";
            case fc.world.GameEvent.SupplyLineEstablished s ->
                    head + "补给线 " + s.line() + " " + nameOf(s.from()) + " → " + nameOf(s.to())
                            + " 每批 " + s.amount().stripTrailingZeros().toPlainString()
                            + " " + s.resource().id();
            case fc.world.GameEvent.ProductionCompleted p ->
                    head + "生产完成 " + p.recipeName() + " 于 " + nameOf(p.where())
                            + "：" + p.outputs();
            case fc.world.GameEvent.MarketPriceShocked m ->
                    head + "价格冲击 " + m.resource().id() + " ×"
                            + m.multiplier().stripTrailingZeros().toPlainString();
            case fc.world.GameEvent.WagePaid w ->
                    head + "工资 " + agentName(w.actor()) + " "
                            + w.amount().stripTrailingZeros().toPlainString() + " 信用点";
            case fc.world.GameEvent.TradeSettled t ->
                    head + "成交 " + t.side() + " " + t.amount().stripTrailingZeros().toPlainString()
                            + " " + t.resource().id() + " @ " + t.unitPrice().toPlainString();
            case fc.world.GameEvent.FuelConsumed f ->
                    head + "燃料消耗 舰船#" + f.ship().id() + " "
                            + f.amount().stripTrailingZeros().toPlainString() + " 于 " + nameOf(f.where());
            default -> head + e.cause() + " " + e.outcome();
        };
    }

    private String agentName(fc.world.Ids.AgentId id) {
        var a = loop.state().agents().get(id);
        return a == null ? ("#" + id.id()) : a.name() + "#" + id.id();
    }

    private String nameOf(Ids.LocationId id) {
        Location l = loop.state().graph().get(id);
        return l == null ? "#" + id.id() : l.name();
    }

    private String doNext(List<String> tokens) {
        if (tokens.size() < 2) {
            return "用法: next <天数>";
        }
        long days;
        try {
            days = Long.parseLong(tokens.get(1));
        } catch (NumberFormatException e) {
            return "天数非法: " + tokens.get(1);
        }
        if (days < 0) {
            return "天数必须是非负整数: " + days;
        }
        if (loop.awaitingDecision()) {
            return "存在待决决策，时间暂停。先 decisions 查看，再用 decide <决策id> <选项id>。";
        }
        Stardate before = loop.now();
        int chronicleBefore = loop.state().chronicle().size();
        Stardate after = loop.next(days);
        long actual = after.day() - before.day();
        int added = loop.state().chronicle().size() - chronicleBefore;
        StringBuilder sb = new StringBuilder();
        if (actual < days) {
            sb.append("因出现待决决策，仅推进 ").append(actual).append(" 天");
        } else {
            sb.append("时间推进 ").append(days).append(" 天");
        }
        sb.append("，当前 ").append(after).append("（").append(after.toIsoDate()).append("）");
        if (added > 0) {
            sb.append("；新增事件 ").append(added).append(" 条");
        }
        var report = loop.lastReport();
        if (!report.isEmpty()) {
            sb.append("\n  ").append(String.join("｜", report));
        }
        var pending = loop.state().pendingDecisions();
        if (!pending.isEmpty()) {
            sb.append("\n  待决决策：");
            for (PendingDecision d : pending) {
                sb.append(d.id()).append(' ').append(d.title()).append("；");
            }
            sb.append(" 用 decisions 查看详情。");
        }
        return sb.toString();
    }

    private String doSail(List<String> tokens) {
        if (tokens.size() < 3) {
            return "用法: sail <舰船id或名字> <目标地点id或名字>";
        }
        WorldState state = loop.state();
        Optional<Location.Ship> ship = findShip(state, tokens.get(1));
        if (ship.isEmpty()) {
            return "找不到舰船: " + tokens.get(1);
        }
        Optional<Long> target = findLocationId(state, tokens.get(2));
        if (target.isEmpty()) {
            return "找不到地点: " + tokens.get(2);
        }
        VoyageService.SailResult r = VoyageService.sail(state, ship.get(), target.get());
        return r.message();
    }

    /** 默认舰船（id 最小的那艘）。 */
    private Optional<Location.Ship> defaultShip(WorldState state) {
        return state.graph().ships().stream()
                .min(Comparator.comparingLong(s -> s.shipId().id()));
    }

    private String doMarket(List<String> tokens) {
        if (trade == null) {
            return "经济系统未启用（世界未配置 <resources>）。";
        }
        WorldState st = loop.state();
        Optional<Location.Ship> ship = tokens.size() > 1
                ? findShip(st, tokens.get(1)) : defaultShip(st);
        if (ship.isEmpty()) {
            return "找不到舰船。";
        }
        if (ship.get().inVoyage()) {
            return "该舰船在航中，无法交易（先 next 抵达）。";
        }
        return trade.marketTable(st, ship.get());
    }

    private String doTrade(List<String> tokens) {
        if (trade == null) {
            return "经济系统未启用（世界未配置 <resources>）。";
        }
        if (tokens.size() < 3) {
            return "用法: trade buy|sell <资源> <数量> [舰船] / trade quote <资源>";
        }
        String side = tokens.get(1);
        String resName = tokens.get(2);
        ResourceKind kind;
        try {
            kind = ResourceKind.goodsFromId(resName);
        } catch (IllegalArgumentException e) {
            return "未知资源: " + resName + "（可选 " + ResourceKind.GOODS.stream()
                    .map(ResourceKind::id).toList() + "）";
        }
        if ("quote".equals(side)) {
            WorldState s0 = loop.state();
            Optional<Location.Ship> sh = tokens.size() > 3 ? findShip(s0, tokens.get(3)) : defaultShip(s0);
            return sh.map(x -> trade.quote(s0, x, kind)).orElse("找不到舰船。");
        }
        if (!"buy".equals(side) && !"sell".equals(side)) {
            return "未知交易方向: " + side + "（buy|sell）";
        }
        if (tokens.size() < 4) {
            return "用法: trade buy|sell <资源> <数量> [舰船]";
        }
        int qty;
        try {
            qty = Integer.parseInt(tokens.get(3));
        } catch (NumberFormatException e) {
            return "数量非法: " + tokens.get(3);
        }
        WorldState st = loop.state();
        Optional<Location.Ship> ship = tokens.size() > 4
                ? findShip(st, tokens.get(4)) : defaultShip(st);
        if (ship.isEmpty()) {
            return "找不到舰船。";
        }
        if (ship.get().inVoyage()) {
            return "该舰船在航中，无法交易（先 next 抵达）。";
        }
        var r = "buy".equals(side)
                ? trade.buy(st, ship.get(), kind, qty)
                : trade.sell(st, ship.get(), kind, qty);
        return r.message();
    }

    private String factions() {
        WorldState st = loop.state();
        if (st.factions().isEmpty()) {
            return "当前世界没有势力（world.xml 的 <factions> 为空）。";
        }
        StringBuilder sb = new StringBuilder("---- 势力 ----\n");
        for (Faction f : st.factions().values()) {
            sb.append("  #").append(f.id().id()).append(' ').append(f.name())
                    .append("（").append(switch (f.kind()) {
                        case TRADE -> "贸易";
                        case COLONY -> "殖民";
                        case MILITARY -> "军事";
                        case SCIENCE -> "科研";
                    }).append("）中心 ").append(nameOf(f.seat()))
                    .append("｜优先 ").append(f.priorityGood().displayName())
                    .append(" 库存 ").append(st.goods(f.seat(), f.priorityGood())
                            .stripTrailingZeros().toPlainString())
                    .append('/').append(f.stockTarget().stripTrailingZeros().toPlainString())
                    .append("｜资金 ").append(st.money().balance(f.account())
                            .stripTrailingZeros().toPlainString())
                    .append("｜声望 ").append(st.reputation(f.id()))
                    .append('\n');
        }
        sb.append("  势力关系：");
        boolean any = false;
        for (Map.Entry<String, Integer> e : new TreeMap<>(st.relations().snapshot()).entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append("  ");
            any = true;
        }
        if (!any) {
            sb.append("（无配置）");
        }
        return sb.toString();
    }

    private String decisions() {
        WorldState st = loop.state();
        if (st.decisions().isEmpty()) {
            return "尚无决策。重大事件（如强离子风暴）会要求你做出选择。";
        }
        StringBuilder sb = new StringBuilder("---- 决策 ----\n");
        for (PendingDecision d : st.decisions()) {
            sb.append("  ").append(d.id()).append(' ').append(d.title())
                    .append(" 于 ").append(nameOf(d.where()))
                    .append(d.resolved() ? "｜已选 " + d.chosenOption() : "｜待决").append('\n');
            sb.append("    ").append(d.description()).append('\n');
            if (!d.resolved()) {
                for (PendingDecision.Option o : d.options()) {
                    sb.append("    - ").append(o.id()).append(' ').append(o.label())
                            .append("：").append(o.description()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private String doDecide(List<String> tokens) {
        if (tokens.size() < 3) {
            return "用法: decide <决策id> <选项id>（decisions 查看可选方案）";
        }
        WorldState st = loop.state();
        var r = DecisionResolver.resolve(st, new Ids.DecisionId(tokens.get(1)), tokens.get(2));
        return r.message();
    }

    private String doColonies(List<String> tokens) {
        WorldState st = loop.state();
        if (tokens.size() > 1) {
            Optional<Long> id = findLocationId(st, tokens.get(1));
            if (id.isEmpty()) {
                return "找不到地点: " + tokens.get(1);
            }
            return ColonyService.describe(st, new Ids.LocationId(id.get()));
        }
        if (st.colonies().isEmpty()) {
            return "当前世界没有殖民地（world.xml 的 <locations> 未配置等级/所有者）。";
        }
        StringBuilder sb = new StringBuilder("---- 殖民地 ----\n");
        for (ColonyState c : st.colonies().values()) {
            sb.append("  ").append(nameOf(c.id()))
                    .append("｜等级 ").append(c.level())
                    .append("｜人口 ").append(c.population())
                    .append(c.playerOwned() ? "｜玩家所有" : "｜非玩家所有")
                    .append(c.isColony() ? "" : "（前哨站）").append('\n');
        }
        return sb.toString();
    }

    private String doUpgrade(List<String> tokens) {
        if (tokens.size() < 2) {
            return "用法: upgrade <地点id或名字>（colonies <地点> 查看升级要求）";
        }
        WorldState st = loop.state();
        Optional<Long> id = findLocationId(st, tokens.get(1));
        if (id.isEmpty()) {
            return "找不到地点: " + tokens.get(1);
        }
        var r = ColonyService.upgrade(st, new Ids.LocationId(id.get()));
        return r.message();
    }

    private String doSupply(List<String> tokens) {
        WorldState st = loop.state();
        if (tokens.size() < 2 || "list".equals(tokens.get(1))) {
            return LogisticsService.list(st);
        }
        if ("cancel".equals(tokens.get(1))) {
            if (tokens.size() < 3) {
                return "用法: supply cancel <id>";
            }
            return LogisticsService.cancel(st, tokens.get(2)).message();
        }
        if ("add".equals(tokens.get(1))) {
            if (tokens.size() < 8) {
                return "用法: supply add <id> <起点> <终点> <资源> <每批数量> <间隔天数>";
            }
            Optional<Long> from = findLocationId(st, tokens.get(3));
            Optional<Long> to = findLocationId(st, tokens.get(4));
            if (from.isEmpty() || to.isEmpty()) {
                return "找不到地点: " + (from.isEmpty() ? tokens.get(3) : tokens.get(4));
            }
            ResourceKind kind;
            try {
                kind = ResourceKind.goodsFromId(tokens.get(5));
            } catch (IllegalArgumentException e) {
                return "未知资源: " + tokens.get(5);
            }
            BigDecimal amount;
            int interval;
            try {
                amount = new BigDecimal(tokens.get(6));
                interval = Integer.parseInt(tokens.get(7));
            } catch (NumberFormatException e) {
                return "每批数量或间隔非法。";
            }
            return LogisticsService.establish(st, tokens.get(2),
                    new Ids.LocationId(from.get()), new Ids.LocationId(to.get()),
                    kind, amount, interval).message();
        }
        return "用法: supply list | add <id> <起点> <终点> <资源> <每批> <间隔> | cancel <id>";
    }

    private String goals() {
        WorldState st = loop.state();
        long day = st.now().day();
        List<SupplyLine> lines = new ArrayList<>(st.supplyLines().values());
        int activeLines = (int) lines.stream().filter(SupplyLine::active).count();
        int colonyLevel = st.playerColonies().stream().mapToInt(ColonyState::level).max().orElse(0);
        int known = 0;
        for (Ids.FactionId fid : st.factions().keySet()) {
            if (st.reputation(fid) >= 20) {
                known++;
            }
        }

        StringBuilder sb = new StringBuilder("---- 阶段目标 ----\n");
        sb.append("  D0 生存期（第 1–30 天）：保持燃料与食物为正循环，"
                + "探索附近 6 个地点，建立第一条补给线\n");
        sb.append("    - 当前第 ").append(day).append(" 天")
                .append(day <= 30 ? "（进行中）" : "（已结束）").append('\n');
        sb.append("    - 已建立补给线 ").append(activeLines).append(" 条")
                .append(activeLines > 0 ? " ✓" : " ✗").append('\n');
        sb.append("  D1 立足期（第 30–100 天）：前哨站升级为殖民地，签下第一条贸易线，"
                + "与一个势力达到“知名”\n");
        sb.append("    - 玩家殖民地最高等级 ").append(colonyLevel)
                .append(colonyLevel >= 1 ? " ✓" : " ✗").append('\n');
        sb.append("    - 已建立补给线（贸易线）").append(activeLines).append(" 条")
                .append(activeLines > 0 ? " ✓" : " ✗").append('\n');
        sb.append("    - 声望 ≥ 20 的势力 ").append(known).append(" 个")
                .append(known > 0 ? " ✓" : " ✗").append('\n');
        sb.append("  资源总量：");
        for (ResourceKind kind : ResourceKind.GOODS) {
            sb.append(kind.id()).append('=')
                    .append(st.ledger().total(kind).stripTrailingZeros().toPlainString()).append(' ');
        }
        return sb.toString();
    }

    private String finance() {
        WorldState st = loop.state();
        MoneyLedger m = st.money();
        StringBuilder sb = new StringBuilder("---- 财务 ----\n");
        sb.append("  玩家余额 ").append(m.balance(MoneyLedger.PLAYER).stripTrailingZeros().toPlainString())
                .append("｜银行 ").append(m.balance(MoneyLedger.BANK).stripTrailingZeros().toPlainString())
                .append("｜家庭 ").append(m.balance(MoneyLedger.HOUSEHOLDS).stripTrailingZeros().toPlainString())
                .append("｜国库 ").append(m.balance(MoneyLedger.TREASURY).stripTrailingZeros().toPlainString())
                .append('\n');
        sb.append("  货币总量 ").append(m.total().stripTrailingZeros().toPlainString())
                .append("｜玩家欠款 ").append(m.debt(MoneyLedger.PLAYER).stripTrailingZeros().toPlainString())
                .append('\n');
        sb.append("  资金流水（最近 10 笔）：\n");
        var txs = m.transactions();
        int from = Math.max(0, txs.size() - 10);
        for (int i = from; i < txs.size(); i++) {
            var t = txs.get(i);
            sb.append("    [").append(t.when()).append("] ").append(t.from()).append(" → ")
                    .append(t.to()).append("  ")
                    .append(t.amount().stripTrailingZeros().toPlainString())
                    .append("（").append(t.reason()).append("）\n");
        }
        if (txs.isEmpty()) {
            sb.append("    （暂无）\n");
        }
        return sb.toString();
    }

    private String doTrace(List<String> tokens) {
        int limit = parseLimit(tokens, 20);
        var all = loop.state().chronicle().all();
        List<String> out = new ArrayList<>();
        for (var e : all) {
            if (e instanceof fc.world.GameEvent.IonStorm
                    || e instanceof fc.world.GameEvent.StormImpact
                    || e instanceof fc.world.GameEvent.DecisionRaised
                    || e instanceof fc.world.GameEvent.DecisionResolved
                    || e instanceof fc.world.GameEvent.FactionAction) {
                out.add(renderEvent(e));
            }
        }
        if (out.isEmpty()) {
            return "暂无可追踪的风暴/势力/决策事件。";
        }
        int from = Math.max(0, out.size() - limit);
        StringBuilder sb = new StringBuilder("---- 重大事件追踪（").append(out.size()).append(" 条）----\n");
        for (int i = from; i < out.size(); i++) {
            sb.append("  ").append(out.get(i)).append('\n');
        }
        return sb.toString();
    }

    private Optional<Location.Ship> findShip(WorldState state, String key) {
        try {
            Optional<Location.Ship> byId = VoyageService.findShip(state, Long.parseLong(key));
            if (byId.isPresent()) {
                return byId;
            }
        } catch (NumberFormatException ignored) {
            // 非数字，按名字找
        }
        return VoyageService.findShipByName(state, key);
    }

    private Optional<Long> findLocationId(WorldState state, String key) {
        try {
            long id = Long.parseLong(key);
            if (state.graph().contains(new Ids.LocationId(id))) {
                return Optional.of(id);
            }
            return Optional.empty();
        } catch (NumberFormatException ignored) {
            // 按名字找
        }
        for (Location l : state.graph().nodes().values()) {
            if (!(l instanceof Location.Ship) && l.name().equals(key)) {
                return Optional.of(l.id().id());
            }
        }
        for (Location l : state.graph().nodes().values()) {
            if (!(l instanceof Location.Ship) && l.name().contains(key)) {
                return Optional.of(l.id().id());
            }
        }
        return Optional.empty();
    }

    private String ships() {
        StringBuilder sb = new StringBuilder("---- 舰队 ----\n");
        for (Location.Ship s : loop.state().graph().ships()) {
            sb.append("  #").append(s.shipId().id()).append(' ').append(s.name());
            if (s.inVoyage()) {
                sb.append(" 在航 ").append(s.voyage().from().id()).append("->").append(s.voyage().to().id())
                        .append(" (").append(s.voyage().elapsedTicks()).append('/')
                        .append(s.voyage().totalTicks()).append(')');
            } else {
                Location at = loop.state().graph().get(s.parent());
                sb.append(" 停泊于 ").append(at == null ? s.parent().id() : at.name());
            }
            sb.append("  燃料=").append(s.cargo().amount(ResourceKind.FUEL)
                            .stripTrailingZeros().toPlainString())
                    .append('\n');
        }
        return sb.toString();
    }

    private String crew() {
        var st = loop.state();
        if (st.agents().isEmpty()) {
            return "当前世界没有主体（world.xml 的 <agents> 为空）。";
        }
        var list = new ArrayList<>(st.agents().values());
        list.sort(Comparator.comparingLong(a -> a.id().id()));
        StringBuilder sb = new StringBuilder("---- 主体 ").append(list.size()).append(" 人 ----\n");
        for (Agent a : list) {
            Location at = st.graph().get(a.location());
            sb.append("  #").append(a.id().id()).append(' ')
                    .append(a.name()).append(' ')
                    .append(switch (a.role()) {
                        case CREW -> "船员";
                        case RESIDENT -> "居民";
                        case ENVOY -> "使者";
                    })
                    .append("@").append(at == null ? a.location().id() : at.name())
                    .append("  饥=").append(a.needs().hunger())
                    .append(" 疲=").append(a.needs().fatigue())
                    .append(" 气=").append(a.needs().morale())
                    .append("  技能=").append(a.skill())
                    .append("  ").append(a.isTraveling()
                            ? "前往 " + nameOf(a.travelDestination()) + "（余 "
                                    + a.travelTicksRemaining() + " 天）"
                            : switch (a.assignment()) {
                                case WORKING -> "工作";
                                case RESTING -> "休息";
                                case IDLE -> "空闲";
                                case TRAVELING -> "在途";
                            })
                    .append("\n");
        }
        return sb.toString();
    }

    private String status() {
        WorldState st = loop.state();
        return "当前 " + st.now() + "（" + st.now().toIsoDate() + "）"
                + "，地点 " + st.graph().placeCount() + " 个"
                + "，航线 " + st.graph().links().size() + " 条"
                + "，舰船 " + st.graph().ships().size() + " 艘"
                + "，主体 " + st.agents().size() + " 人"
                + "，势力 " + st.factions().size() + " 个"
                + "，编年史 " + st.chronicle().size() + " 条"
                + "，待决决策 " + st.pendingDecisions().size() + " 个"
                + "，信用点 " + st.credits().stripTrailingZeros().toPlainString();
    }

    private String help() {
        int total = COMMANDS.stream().mapToInt(c -> 1 + c.aliases().size()).sum();
        StringBuilder sb = new StringBuilder();
        sb.append("全部命令（").append(COMMANDS.size()).append(" 条，含别名共 ")
                .append(total).append(" 个）：\n");
        for (Cmd c : COMMANDS) {
            sb.append("  ").append(pad(c.usage())).append(c.desc()).append('\n');
            for (String alias : c.aliases()) {
                String aliasUsage = alias + c.usage().substring(c.name().length());
                sb.append("  ").append(pad(aliasUsage)).append("同 ").append(c.name()).append('\n');
            }
        }
        sb.append("""

                常用流程:
                  map / crew / ships          查看世界、主体与舰队
                  market / trade buy|sell     在舰船所在地点买卖
                  sail <舰船> <地点>           启航；next 推进到抵港
                  supply add ...             建立定期补给线
                  colonies / upgrade         查看并升级前哨站
                  decisions / decide         处理重大事件提出的选择
                  goals / finance / hash     进度、财务与确定性校验""");
        return sb.toString();
    }

    /** 按显示宽度左对齐（CJK 记 2 列），保证中文用法也整齐。 */
    private static String pad(String s) {
        int width = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            width += (cp >= 0x2E80) ? 2 : 1;
            i += Character.charCount(cp);
        }
        int target = 28;
        return width >= target ? s + "  " : s + " ".repeat(target - width);
    }
}
