package fc.cli;

import fc.colony.ColonyService;
import fc.colony.LogisticsService;
import fc.colony.ProjectService;
import fc.colony.ShipyardService;
import fc.diplomacy.CommissionService;
import fc.diplomacy.DiplomacyService;
import fc.diplomacy.GoalService;
import fc.economy.LoanService;
import fc.economy.TradeService;
import fc.fleet.CrewService;
import fc.fleet.MaintenanceService;
import fc.fleet.VoyageService;
import fc.history.jdbc.ChronicleDriver;
import fc.history.jdbc.ChronicleSessions;
import fc.history.query.ChronicleExport;
import fc.history.query.ChronicleQuery;
import fc.science.ResearchService;
import fc.science.SurveyService;
import fc.sim.DecisionResolver;
import fc.sim.GameLoop;
import fc.sim.StateHasher;
import fc.sim.tick.TickContext;
import fc.world.Agent;
import fc.world.ColonyState;
import fc.world.Commission;
import fc.world.Faction;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.KnowledgeState;
import fc.world.Location;
import fc.world.MoneyLedger;
import fc.world.PendingDecision;
import fc.world.ResourceKind;
import fc.world.SupplyLine;
import fc.world.Treaty;
import fc.world.WorldState;
import fc.world.time.Stardate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

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
                    "阶段目标进度（D0 生存 → D4 终局）"),
            new Cmd("scan", List.of(), "scan [舰船]",
                    "近距扫描：揭示相邻的未知星域信号"),
            new Cmd("survey", List.of(), "survey list|start <舰船> <地点>|cancel <舰船>",
                    "星图测绘：把已勘察地点测为已测绘并产出科研数据"),
            new Cmd("research", List.of("techs"), "research [start <科技> [实验室]|cancel <实验室>]",
                    "科技树、在研项目与立项（techs 只看科技树）"),
            new Cmd("assign", List.of(), "assign <主体> <舰船|地点>",
                    "在同一停泊点内调动船员/居民"),
            new Cmd("repair", List.of(), "repair <舰船>",
                    "在具备维修能力的停泊点修复船体"),
            new Cmd("order", List.of(), "order [ships|list|ship <船型> [名称]]",
                    "船坞：船型列表、订单状态与下单建造"),
            new Cmd("project", List.of(), "project [gate|beacon]",
                    "终局工程：进度面板，或启动星门/边境信标"),
            new Cmd("diplomacy", List.of(), "diplomacy",
                    "外交面板：声望、级差、税率与条约"),
            new Cmd("gift", List.of(), "gift <势力> <信用点>",
                    "向势力中心赠礼提升声望（需要舰船停泊）"),
            new Cmd("treaty", List.of(), "treaty <势力> trade|nonaggression|alliance",
                    "与势力签署贸易/互不侵犯/同盟条约"),
            new Cmd("threaten", List.of(), "threaten <势力>",
                    "威胁势力中心，立即进入冲突结算"),
            new Cmd("commission", List.of(), "commission list|accept <id>|deliver <id> [舰船]",
                    "势力委托：查看、接取与交付"),
            new Cmd("finance", List.of(), "finance",
                    "财务：账户余额、贷款与最近流水"),
            new Cmd("borrow", List.of(), "borrow <金额>",
                    "向银行借款（单笔与未偿总额不得超过银行余额）"),
            new Cmd("repay", List.of(), "repay <金额>",
                    "偿还银行贷款（不得超过债务或余额）"),
            new Cmd("hash", List.of(), "hash",
                    "当前世界状态哈希（确定性回放校验）"),
            new Cmd("trace", List.of(), "trace [条数]",
                    "追踪风暴因果链与重大事件"),
            new Cmd("log", List.of("chronicle"), "log [条数|all]",
                    "查看编年史（默认最近 8 条；all 看全部）"),
            new Cmd("query", List.of(), "query <SQL>",
                    "只读 SQL 查询编年史（SELECT ... FROM chronicle）"),
            new Cmd("export", List.of(), "export <csv|xml|html|zip> [路径]",
                    "导出编年史（默认 chronicle.<格式>）"),
            new Cmd("status", List.of(), "status",
                    "世界概况：时间、地点数、舰船数、事件数"),
            new Cmd("help", List.of(), "help",
                    "显示全部命令与本说明"),
            new Cmd("quit", List.of("exit"), "quit",
                    "退出游戏（exit 同义）")
    );

    private final GameLoop loop;
    private final TradeService trade;
    private final ShipyardService shipyards;
    private final GoalService goals;
    private final Supplier<TickContext> tickContexts;

    public Cli(GameLoop loop) {
        this(loop, null, null, null, null);
    }

    public Cli(GameLoop loop, TradeService trade) {
        this(loop, trade, null, null, null);
    }

    public Cli(GameLoop loop, TradeService trade, ShipyardService shipyards, GoalService goals) {
        this(loop, trade, shipyards, goals, null);
    }

    public Cli(GameLoop loop, TradeService trade, ShipyardService shipyards,
               GoalService goals, Supplier<TickContext> tickContexts) {
        this.loop = loop;
        this.trade = trade;
        this.shipyards = shipyards;
        this.goals = goals;
        this.tickContexts = tickContexts;
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
            case "scan" -> doScan(tokens);
            case "survey" -> doSurvey(tokens);
            case "research" -> doResearch(tokens);
            case "assign" -> doAssign(tokens);
            case "repair" -> doRepair(tokens);
            case "order" -> doOrder(tokens);
            case "project" -> doProject(tokens);
            case "diplomacy" -> doDiplomacy();
            case "gift" -> doGift(tokens);
            case "treaty" -> doTreaty(tokens);
            case "threaten" -> doThreaten(tokens);
            case "commission" -> doCommission(tokens);
            case "finance" -> finance();
            case "borrow" -> doBorrow(tokens);
            case "repay" -> doRepay(tokens);
            case "hash" -> StateHasher.hash(loop.state());
            case "trace" -> doTrace(tokens);
            case "log" -> doChronicle(tokens);
            case "query" -> doQuery(tokens);
            case "export" -> doExport(tokens);
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

    /** 只读 SQL：把编年史挂到匿名 JDBC 会话上执行，输出表格。 */
    private String doQuery(List<String> tokens) {
        if (tokens.size() < 2) {
            return "用法：query <SQL>（例：query SELECT type, day FROM chronicle WHERE category = 'Fleet'）";
        }
        String sql = String.join(" ", tokens.subList(1, tokens.size()));
        String session = ChronicleSessions.bindAnonymous(loop.state().chronicle());
        try (Connection connection = ChronicleDriver.connectTo(session);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int columns = meta.getColumnCount();
            StringBuilder sb = new StringBuilder("---- query ----\n  ");
            for (int i = 1; i <= columns; i++) {
                if (i > 1) {
                    sb.append(" | ");
                }
                sb.append(meta.getColumnLabel(i));
            }
            sb.append('\n');
            int rows = 0;
            boolean truncated = false;
            while (rs.next()) {
                if (rows >= 100) {
                    truncated = true;
                    break;
                }
                sb.append("  ");
                for (int i = 1; i <= columns; i++) {
                    if (i > 1) {
                        sb.append(" | ");
                    }
                    String value = rs.getString(i);
                    sb.append(value == null ? "NULL" : value);
                }
                sb.append('\n');
                rows++;
            }
            sb.append("  共 ").append(rows).append(" 行");
            if (truncated) {
                sb.append("（仅显示前 100 行，请加 LIMIT）");
            }
            return sb.toString();
        } catch (SQLException e) {
            return "查询失败：" + e.getMessage();
        } finally {
            ChronicleSessions.unbind(session);
        }
    }

    /** 导出编年史：csv/xml/html/zip；默认文件名 chronicle.<格式>。 */
    private String doExport(List<String> tokens) {
        if (tokens.size() < 2) {
            return "用法：export <csv|xml|html|zip> [路径]";
        }
        String format = tokens.get(1).toLowerCase(java.util.Locale.ROOT);
        if (!List.of("csv", "xml", "html", "zip").contains(format)) {
            return "未知导出格式：" + tokens.get(1) + "（可用 csv/xml/html/zip）";
        }
        Path output = tokens.size() >= 3 ? Path.of(tokens.get(2)) : Path.of("chronicle." + format);
        var chronicle = loop.state().chronicle();
        try {
            if (format.equals("html")) {
                Path parent = output.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Path work = Files.createTempDirectory("fc-export-");
                try {
                    String html = ChronicleExport.toHtml(chronicle, work.resolve("chronicle.xml"));
                    Files.writeString(output, html, StandardCharsets.UTF_8);
                } finally {
                    try (var walk = Files.walk(work)) {
                        for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                            Files.deleteIfExists(p);
                        }
                    }
                }
                return "已导出 " + chronicle.all().size() + " 条事件 → " + output;
            }
            if (format.equals("csv")) {
                ChronicleExport.toCsv(chronicle, output, ChronicleQuery.all());
            } else if (format.equals("xml")) {
                ChronicleExport.toXml(chronicle, output, ChronicleQuery.all());
            } else {
                ChronicleExport.toZip(chronicle, output, ChronicleQuery.all());
            }
            return "已导出 " + chronicle.all().size() + " 条事件 → " + output;
        } catch (IOException e) {
            return "导出失败：" + e.getMessage();
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
            case fc.world.GameEvent.VoyageIncident v ->
                    head + "航行事故 舰船#" + v.ship().id() + " " + v.kind()
                            + "（延误 " + v.delayDays() + " 天，货损 "
                            + v.cargoLoss().stripTrailingZeros().toPlainString()
                            + "，耐久 -" + v.hullDamage() + "）";
            case fc.world.GameEvent.ShipRepaired r ->
                    head + "维修 舰船#" + r.ship().id() + " +" + r.repaired()
                            + "（耐久 " + r.hull() + "）于 " + nameOf(r.where());
            case fc.world.GameEvent.KnowledgeGained k ->
                    head + "星图 " + nameOf(k.where()) + " → " + k.state().displayName()
                            + "：" + k.detail();
            case fc.world.GameEvent.SurveyStarted s ->
                    head + "测绘开始 " + nameOf(s.where()) + " 舰船#" + s.ship().id()
                            + "（预计 " + s.days() + " 天）";
            case fc.world.GameEvent.SurveyCompleted s ->
                    head + "测绘完成 " + nameOf(s.where()) + "：科研数据 +" + s.researchYield()
                            + (s.discovery() == null || s.discovery().isBlank()
                                    ? "" : "；" + s.discovery());
            case fc.world.GameEvent.DiscoveryMade d ->
                    head + "探索发现 " + nameOf(d.where()) + "：" + d.detail();
            case fc.world.GameEvent.ResearchStarted r ->
                    head + "科研立项 " + r.techId() + "（" + r.techName() + "，"
                            + r.days() + " 天）于 " + nameOf(r.where());
            case fc.world.GameEvent.TechUnlocked t ->
                    head + "科技解锁 " + t.techId() + " " + t.techName() + "：" + t.effect();
            case fc.world.GameEvent.DiplomacyAction a ->
                    head + "外交 " + nameOf(a.where()) + "：" + a.action() + "｜" + a.detail();
            case fc.world.GameEvent.TreatySigned t ->
                    head + "条约 " + nameOf(t.where()) + "：" + t.treaty().displayName()
                            + "（花费 " + t.cost().stripTrailingZeros().toPlainString() + "）";
            case fc.world.GameEvent.CommissionOffered c ->
                    head + "委托 " + c.commission() + "："
                            + c.amount().stripTrailingZeros().toPlainString() + " "
                            + c.resource().id() + " → " + nameOf(c.to())
                            + "，截止 " + c.deadline().toIsoDate() + "，报酬 "
                            + c.reward().stripTrailingZeros().toPlainString();
            case fc.world.GameEvent.CommissionAccepted c ->
                    head + "接取委托 " + c.commission();
            case fc.world.GameEvent.CommissionCompleted c ->
                    head + "完成委托 " + c.commission() + "：报酬 "
                            + c.reward().stripTrailingZeros().toPlainString()
                            + "，声望 +" + c.reputation();
            case fc.world.GameEvent.CommissionFailed c ->
                    head + "委托逾期 " + c.commission() + "：声望 " + c.reputation();
            case fc.world.GameEvent.ConflictResolved c ->
                    head + "冲突 " + nameOf(c.where()) + "：" + c.outcome();
            case fc.world.GameEvent.ShipOrdered o ->
                    head + "船坞订单 " + o.order() + " " + o.shipType()
                            + "（" + o.days() + " 天，" + o.cost()
                                    .stripTrailingZeros().toPlainString() + "）于 " + nameOf(o.where());
            case fc.world.GameEvent.ShipDelivered d ->
                    head + "舰船交付 " + d.shipName() + "（舰船#" + d.ship().id() + "）于 "
                            + nameOf(d.where());
            case fc.world.GameEvent.ProjectStarted p ->
                    head + "终局工程 " + p.project() + " 启动于 " + nameOf(p.where())
                            + "（" + p.days() + " 天）";
            case fc.world.GameEvent.ProjectCompleted p ->
                    head + "终局工程完成 " + p.project() + " 于 " + nameOf(p.where());
            case fc.world.GameEvent.EndingReached x ->
                    head + "结局 " + x.ending() + "：" + x.detail();
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
                    .append(' ').append(DiplomacyService.Tier.of(st.reputation(f.id()))
                            .displayName())
                    .append("｜税率 ").append(st.taxRateAt(f.seat())
                            .stripTrailingZeros().toPlainString())
                    .append("｜条约 ");
            var treaties = st.treaties().get(f.id());
            if (treaties == null || treaties.isEmpty()) {
                sb.append("（无）");
            } else {
                treaties.stream().sorted().forEach(t -> sb.append(t.displayName()).append(' '));
            }
            sb.append('\n');
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

    private String doScan(List<String> tokens) {
        WorldState st = loop.state();
        Optional<Location.Ship> ship = tokens.size() > 1
                ? findShip(st, tokens.get(1)) : defaultShip(st);
        if (ship.isEmpty()) {
            return "找不到舰船。";
        }
        return SurveyService.scan(st, ship.get()).message();
    }

    private String doSurvey(List<String> tokens) {
        WorldState st = loop.state();
        if (tokens.size() < 2 || "list".equals(tokens.get(1))) {
            return SurveyService.list(st);
        }
        String action = tokens.get(1);
        if ("cancel".equals(action)) {
            if (tokens.size() < 3) {
                return "用法: survey cancel <舰船>";
            }
            Optional<Location.Ship> ship = findShip(st, tokens.get(2));
            if (ship.isEmpty()) {
                return "找不到舰船: " + tokens.get(2);
            }
            return SurveyService.cancel(st, ship.get()).message();
        }
        if ("start".equals(action)) {
            if (tokens.size() < 4) {
                return "用法: survey start <舰船> <目标地点>";
            }
            Optional<Location.Ship> ship = findShip(st, tokens.get(2));
            if (ship.isEmpty()) {
                return "找不到舰船: " + tokens.get(2);
            }
            Optional<Long> target = findLocationId(st, tokens.get(3));
            if (target.isEmpty()) {
                return "找不到地点: " + tokens.get(3);
            }
            return SurveyService.start(st, ship.get(), new Ids.LocationId(target.get())).message();
        }
        return "用法: survey list | start <舰船> <地点> | cancel <舰船>";
    }

    private String doResearch(List<String> tokens) {
        WorldState st = loop.state();
        if (tokens.size() < 2) {
            return ResearchService.technologies(st) + "\n" + ResearchService.status(st);
        }
        String action = tokens.get(1);
        if ("start".equals(action)) {
            if (tokens.size() < 3) {
                return "用法: research start <科技id> [实验室]";
            }
            Ids.LocationId lab;
            if (tokens.size() > 3) {
                Optional<Long> id = findLocationId(st, tokens.get(3));
                if (id.isEmpty()) {
                    return "找不到实验室: " + tokens.get(3);
                }
                lab = new Ids.LocationId(id.get());
            } else {
                lab = null;
                for (Ids.LocationId id : ResearchService.labs(st)) {
                    if (!st.agentsAt(id).isEmpty()) {
                        lab = id;
                        break;
                    }
                }
                if (lab == null) {
                    List<Ids.LocationId> all = ResearchService.labs(st);
                    lab = all.isEmpty() ? null : all.get(0);
                }
                if (lab == null) {
                    return "世界中没有任何实验室（facilityKind=LAB）";
                }
            }
            return ResearchService.start(st, tokens.get(2), lab).message();
        }
        if ("cancel".equals(action)) {
            if (tokens.size() < 3) {
                return "用法: research cancel <实验室>";
            }
            Optional<Long> id = findLocationId(st, tokens.get(2));
            if (id.isEmpty()) {
                return "找不到实验室: " + tokens.get(2);
            }
            return ResearchService.cancel(st, new Ids.LocationId(id.get())).message();
        }
        if ("status".equals(action)) {
            return ResearchService.status(st);
        }
        return "用法: research [start <科技> [实验室] | cancel <实验室> | status]";
    }

    private String doAssign(List<String> tokens) {
        if (tokens.size() < 3) {
            return "用法: assign <主体id> <舰船或地点id/名字>";
        }
        WorldState st = loop.state();
        long agentId;
        try {
            agentId = Long.parseLong(tokens.get(1));
        } catch (NumberFormatException e) {
            return "主体 id 非法: " + tokens.get(1) + "（crew 查看 id）";
        }
        Optional<Long> target = findAnyLocationId(st, tokens.get(2));
        if (target.isEmpty()) {
            return "找不到目标位置: " + tokens.get(2);
        }
        return CrewService.assign(st, new Ids.AgentId(agentId),
                new Ids.LocationId(target.get())).message();
    }

    private String doRepair(List<String> tokens) {
        WorldState st = loop.state();
        Optional<Location.Ship> ship = tokens.size() > 1
                ? findShip(st, tokens.get(1)) : defaultShip(st);
        if (ship.isEmpty()) {
            return "找不到舰船。";
        }
        return MaintenanceService.repair(st, ship.get()).message();
    }

    private String doOrder(List<String> tokens) {
        WorldState st = loop.state();
        if (shipyards == null) {
            return "船坞系统未启用。";
        }
        if (tokens.size() < 2 || "ships".equals(tokens.get(1))) {
            StringBuilder sb = new StringBuilder("---- 可造船型 ----\n");
            for (fc.config.world.WorldDefinition.ShipTypeDef t : shipyards.types()) {
                sb.append("  ").append(t.id()).append(' ').append(t.name())
                        .append("｜货舱 ").append(t.cargoCapacity().stripTrailingZeros().toPlainString())
                        .append("｜铺位 ").append(t.crewCapacity())
                        .append("｜耐久 ").append(t.maxHull())
                        .append("｜造价 ").append(ShipyardService.costOf(t)
                                .stripTrailingZeros().toPlainString())
                        .append("（材料 ").append(ShipyardService.materialOf(t)
                                .stripTrailingZeros().toPlainString())
                        .append("，设备 ").append(ShipyardService.equipmentOf(t)
                                .stripTrailingZeros().toPlainString())
                        .append("）\n");
            }
            sb.append("  用 order ship <船型> [名称] 下单；order list 查看订单。");
            return sb.toString();
        }
        if ("list".equals(tokens.get(1))) {
            return shipyards.panel(st);
        }
        if ("ship".equals(tokens.get(1))) {
            if (tokens.size() < 3) {
                return "用法: order ship <船型> [名称]";
            }
            String name = tokens.size() > 3
                    ? String.join(" ", tokens.subList(3, tokens.size())) : null;
            return shipyards.order(st, tokens.get(2), name).message();
        }
        return "用法: order [ships | list | ship <船型> [名称]]";
    }

    private String doProject(List<String> tokens) {
        WorldState st = loop.state();
        if (tokens.size() < 2) {
            return ProjectService.panel(st);
        }
        String arg = tokens.get(1).toLowerCase();
        if ("gate".equals(arg) || "星门".equals(arg)) {
            return ProjectService.startGate(st).message();
        }
        if ("beacon".equals(arg) || "信标".equals(arg)) {
            return ProjectService.startBeacon(st).message();
        }
        return "用法: project [gate|beacon]";
    }

    private String doDiplomacy() {
        WorldState st = loop.state();
        if (st.factions().isEmpty()) {
            return "当前世界没有势力。";
        }
        return DiplomacyService.panel(st);
    }

    private String doGift(List<String> tokens) {
        if (tokens.size() < 3) {
            return "用法: gift <势力id或名字> <信用点>";
        }
        WorldState st = loop.state();
        Optional<Ids.FactionId> fid = findFactionId(st, tokens.get(1));
        if (fid.isEmpty()) {
            return "找不到势力: " + tokens.get(1);
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(tokens.get(2));
        } catch (NumberFormatException e) {
            return "金额非法: " + tokens.get(2);
        }
        return DiplomacyService.gift(st, fid.get(), amount).message();
    }

    private String doTreaty(List<String> tokens) {
        if (tokens.size() < 3) {
            return "用法: treaty <势力> trade|nonaggression|alliance";
        }
        WorldState st = loop.state();
        Optional<Ids.FactionId> fid = findFactionId(st, tokens.get(1));
        if (fid.isEmpty()) {
            return "找不到势力: " + tokens.get(1);
        }
        Treaty treaty = switch (tokens.get(2).toLowerCase()) {
            case "trade", "贸易" -> Treaty.TRADE;
            case "nonaggression", "nap", "互不侵犯" -> Treaty.NON_AGGRESSION;
            case "alliance", "同盟" -> Treaty.ALLIANCE;
            default -> null;
        };
        if (treaty == null) {
            return "未知条约类型: " + tokens.get(2) + "（trade|nonaggression|alliance）";
        }
        return DiplomacyService.treaty(st, fid.get(), treaty).message();
    }

    private String doThreaten(List<String> tokens) {
        if (tokens.size() < 2) {
            return "用法: threaten <势力>";
        }
        WorldState st = loop.state();
        Optional<Ids.FactionId> fid = findFactionId(st, tokens.get(1));
        if (fid.isEmpty()) {
            return "找不到势力: " + tokens.get(1);
        }
        TickContext ctx = tickContexts != null ? tickContexts.get()
                : new TickContext(Map.of(), fc.sim.rng.RngStreams.of(st.now().day() + 7919L));
        var result = DiplomacyService.threaten(st, ctx, fid.get());
        for (GameEvent e : ctx.events().drain()) {
            st.chronicle().record(e);
        }
        return result.message();
    }

    private String doCommission(List<String> tokens) {
        WorldState st = loop.state();
        if (tokens.size() < 2 || "list".equals(tokens.get(1))) {
            return CommissionService.list(st);
        }
        String action = tokens.get(1);
        if ("accept".equals(action)) {
            if (tokens.size() < 3) {
                return "用法: commission accept <id>";
            }
            return CommissionService.accept(st, tokens.get(2)).message();
        }
        if ("deliver".equals(action)) {
            if (tokens.size() < 3) {
                return "用法: commission deliver <id> [舰船]";
            }
            Optional<Location.Ship> ship = tokens.size() > 3
                    ? findShip(st, tokens.get(3)) : Optional.empty();
            return CommissionService.deliver(st, tokens.get(2), ship.orElse(null)).message();
        }
        return "用法: commission list | accept <id> | deliver <id> [舰船]";
    }

    private String doBorrow(List<String> tokens) {
        if (tokens.size() < 2) {
            return "用法: borrow <金额>";
        }
        try {
            return LoanService.borrow(loop.state(), new BigDecimal(tokens.get(1))).message();
        } catch (NumberFormatException e) {
            return "金额非法: " + tokens.get(1);
        }
    }

    private String doRepay(List<String> tokens) {
        if (tokens.size() < 2) {
            return "用法: repay <金额>";
        }
        try {
            return LoanService.repay(loop.state(), new BigDecimal(tokens.get(1))).message();
        } catch (NumberFormatException e) {
            return "金额非法: " + tokens.get(1);
        }
    }

    private String goals() {
        if (goals != null) {
            return goals.panel(loop.state());
        }
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

    /** 按 id、精确名字或名字片段找势力。 */
    private Optional<Ids.FactionId> findFactionId(WorldState state, String key) {
        try {
            Ids.FactionId id = new Ids.FactionId(Long.parseLong(key));
            if (state.faction(id) != null) {
                return Optional.of(id);
            }
        } catch (NumberFormatException ignored) {
            // 按名字找
        }
        for (Faction f : state.factions().values()) {
            if (f.name().equals(key)) {
                return Optional.of(f.id());
            }
        }
        for (Faction f : state.factions().values()) {
            if (f.name().contains(key)) {
                return Optional.of(f.id());
            }
        }
        return Optional.empty();
    }

    /** 在所有节点（含舰船）中按 id 或名字解析位置。 */
    private Optional<Long> findAnyLocationId(WorldState state, String key) {
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
        for (Location.Ship s : state.graph().ships()) {
            if (s.name().equals(key)) {
                return Optional.of(s.id().id());
            }
        }
        for (Location l : state.graph().nodes().values()) {
            if (!(l instanceof Location.Ship) && l.name().contains(key)) {
                return Optional.of(l.id().id());
            }
        }
        for (Location.Ship s : state.graph().ships()) {
            if (s.name().contains(key)) {
                return Optional.of(s.id().id());
            }
        }
        return Optional.empty();
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
            sb.append("  #").append(s.shipId().id()).append(' ').append(s.name())
                    .append(" [").append(s.shipType()).append(']');
            if (s.inVoyage()) {
                sb.append(" 在航 ").append(s.voyage().from().id()).append("->").append(s.voyage().to().id())
                        .append(" (").append(s.voyage().elapsedTicks()).append('/')
                        .append(s.voyage().totalTicks()).append(')');
            } else {
                Location at = loop.state().graph().get(s.parent());
                sb.append(" 停泊于 ").append(at == null ? s.parent().id() : at.name());
            }
            sb.append("  耐久=").append(s.hull()).append('/').append(s.maxHull())
                    .append("  货舱=").append(s.cargoUsed().stripTrailingZeros().toPlainString())
                    .append('/').append(s.cargoCapacity().stripTrailingZeros().toPlainString())
                    .append("  船员=").append(loop.state().agentsAt(s.id()).size())
                    .append('/').append(s.crewCapacity())
                    .append("  燃料=").append(s.cargo().amount(ResourceKind.FUEL)
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
                    .append("  技能=").append(a.skills().display())
                    .append("  ").append(a.isTraveling()
                            ? "前往 " + nameOf(a.travelDestination()) + "（余 "
                                    + a.travelTicksRemaining() + " 天）"
                            : switch (a.assignment()) {
                                case WORKING -> "工作";
                                case RESTING -> "休息";
                                case IDLE -> "空闲";
                                case TRAVELING -> "在途";
                                case RESEARCH -> "科研";
                                case SURVEYING -> "测绘";
                                case REPAIRING -> "维修";
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
                + "，已知地点 " + st.locationsWithKnowledge(KnowledgeState.DETECTED).size()
                + "/" + st.graph().placeCount()
                + "，科技 " + st.techs().size() + " 项"
                + "，信用点 " + st.credits().stripTrailingZeros().toPlainString()
                + (st.ending() == null ? "" : "，结局 " + st.ending());
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
                  scan / survey               揭示信号、测绘星图
                  research start <科技>       投入科研数据立项
                  assign <主体> <舰船|地点>     在同一停泊点内调动船员
                  repair <舰船> / order ship  维修与船坞建造
                  commission / gift / treaty  委托与外交
                  project gate|beacon         启动星门或边境信标
                  borrow / repay              银行贷款
                  goals / finance / hash     进度、财务与确定性校验

                启动器命令（由 fc-runtime 提供）:
                  save <槽位> [口令]          写入重放存档（默认 saves/ 目录）
                  load <槽位> [口令]          读档并校验状态哈希
                  saves                      列出存档槽位
                  png [路径]                 导出星图 PNG（默认 starmap.png）
                启动参数：--world <文件>、--serve[=<端口>]（只读状态端点）
                JMX：fc.telemetry:type=Game""");
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
