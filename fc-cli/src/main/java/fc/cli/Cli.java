package fc.cli;

import fc.economy.TradeService;
import fc.fleet.VoyageService;
import fc.sim.GameLoop;
import fc.world.Ids;
import fc.world.Location;
import fc.world.WorldState;
import fc.world.time.Stardate;

import java.util.List;
import java.util.Optional;

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
                    "主体名册：角色、位置、饥饿/疲劳/士气、当前指派"),
            new Cmd("sail", List.of(), "sail <舰船> <目标地点>",
                    "启航（舰船与目标都可用 id 或名字）"),
            new Cmd("market", List.of(), "market [舰船]",
                    "当前地点行情：库存、买入价、卖出价"),
            new Cmd("trade", List.of(), "trade buy|sell <资源> <数量> [舰船]",
                    "按行情买卖（舰船须停泊在有货的地点）"),
            new Cmd("next", List.of(), "next <天数>",
                    "推进世界时间（移动与产出在推进中结算）"),
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
            case "log" -> doChronicle(tokens);
            case "help" -> help();
            case "quit" -> "bye";
            default -> "未知命令: " + tokens.get(0) + "（输入 help 查看全部 " + COMMANDS.size() + " 条命令）";
        };
    }

    /** 查看最近事件（默认 8 条；更早的折叠并提示）。命令名 log（chronicle 同义）。 */
    private String doChronicle(List<String> tokens) {
        int limit = 8;
        if (tokens.size() > 1) {
            String arg = tokens.get(1);
            if ("all".equalsIgnoreCase(arg)) {
                limit = Integer.MAX_VALUE;
            } else {
                try {
                    limit = Math.max(1, Integer.parseInt(arg));
                } catch (NumberFormatException e) {
                    return "条数非法: " + arg + "（用法: log [条数|all]）";
                }
            }
        }
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
            case fc.world.GameEvent.CrewAte x ->
                    head + "进食 " + agentName(x.actor()) + "（饥饿 " + x.hungerBefore() + "）于 "
                            + nameOf(x.where());
            case fc.world.GameEvent.CrewMigrated x ->
                    head + "迁徙 " + agentName(x.actor()) + " " + nameOf(x.where())
                            + " → " + nameOf(x.to()) + "（" + x.reason() + "）";
            case fc.world.GameEvent.CrewRefusedDuty x ->
                    head + "拒绝指令 " + agentName(x.actor()) + "：" + x.reason();
            case fc.world.GameEvent.CrewStarving x ->
                    head + "饥饿告急 " + agentName(x.actor()) + "（" + x.hunger() + "）于 "
                            + nameOf(x.where());
            case fc.world.GameEvent.Wedding w ->
                    head + "婚礼 于 " + nameOf(w.where());
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
        int before = loop.state().chronicle().size();
        Stardate after = loop.next(days);
        int added = loop.state().chronicle().size() - before;
        StringBuilder sb = new StringBuilder();
        sb.append("时间推进 ").append(days).append(" 天，当前 ").append(after)
                .append("（").append(after.toIsoDate()).append("）");
        if (added > 0) {
            sb.append("；新增事件 ").append(added).append(" 条");
        }
        var report = loop.lastReport();
        if (!report.isEmpty()) {
            sb.append("\n  ").append(String.join("｜", report));
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
                .min(java.util.Comparator.comparingLong(s -> s.shipId().id()));
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
        String resource = tokens.get(2);
        if ("quote".equals(side)) {
            WorldState s0 = loop.state();
            Optional<Location.Ship> sh = tokens.size() > 3 ? findShip(s0, tokens.get(3)) : defaultShip(s0);
            return sh.map(x -> trade.quote(s0, x, resource)).orElse("找不到舰船。");
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
        var r = switch (side) {
            case "buy" -> trade.buy(st, ship.get(), resource, qty);
            case "sell" -> trade.sell(st, ship.get(), resource, qty);
            case "quote" -> null;
            default -> null;
        };
        if (r == null) {
            if ("quote".equals(side)) {
                return trade.quote(st, ship.get(), resource);
            }
            return "未知交易方向: " + side + "（buy|sell）";
        }
        return r.message();
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
            if (!(l instanceof Location.Ship) && (l.name().equals(key) || l.name().contains(key))) {
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
            sb.append("  燃料=").append(s.cargo().amount("fuel").stripTrailingZeros().toPlainString())
                    .append('\n');
        }
        return sb.toString();
    }

    private String crew() {
        var st = loop.state();
        if (st.agents().isEmpty()) {
            return "当前世界没有主体（world.xml 的 <agents> 为空）。";
        }
        var list = new java.util.ArrayList<>(st.agents().values());
        list.sort(java.util.Comparator.comparingLong(a -> a.id().id()));
        StringBuilder sb = new StringBuilder("---- 主体 ").append(list.size()).append(" 人 ----\n");
        for (fc.world.Agent a : list) {
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
                    .append("  ").append(switch (a.assignment()) {
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
                + "，编年史 " + st.chronicle().size() + " 条"
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

                跑商开局（低买高卖）:
                  1) market                  看 Sol 行情（粮食便宜、燃料为零）
                  2) trade buy food 100      在 Sol 买粮
                  3) sail 1 8                去熔炉殖民地（当地粮贵）
                  4) next 14                 航行抵港
                  5) trade sell food 100     卖出获利（买方资金不足会部分成交）
                  6) market                  再买 fuel / alloy，继续跑
                  7) log                     看编年史（自主生产、迁徙、风暴）""");
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
