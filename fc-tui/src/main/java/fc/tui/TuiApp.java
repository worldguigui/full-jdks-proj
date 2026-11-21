package fc.tui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 星际远征指挥终端：TUI 是 CLI 的可视化层，而不是另一套命令系统。
 *
 * <p>渲染走 {@link ScreenBuffer} + {@link DiffRenderer}；页面只读 {@link Snapshot}；
 * 所有世界推进/操作都通过 {@link TuiGame#execute(String)} 进入游戏应用层。
 * 页面命令直接复用 CLI 命令名（map/ships/colonies/research/market/log/status/
 * decisions/help）：执行后切到对应可视化页面。双窗口模式下 TUI 只读，完整输出
 * 由独立的 CLI 窗口显示；单窗口模式在状态行显示最后反馈。
 * 界面刷新与游戏时间分离，渲染从不推进 Tick。</p>
 *
 * <p>输入采用标准输入逐行读取（Java SE 21 无跨平台 Raw Mode）。</p>
 */
public final class TuiApp implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(TuiApp.class.getName());
    private static final String PROMPT = "COMMAND > ";
    private static final Set<String> PAGE_COMMANDS = Set.of(
            "map", "ships", "colonies", "research", "market", "log",
            "status", "decisions", "help");

    /** 页面标识。 */
    public enum Page {
        BRIDGE, MAP, FLEET, COLONY, RESEARCH, TRADE, INTEL, HELP
    }

    private final TuiGame game;
    private final Terminal terminal;
    private final CharSet chars;
    private final DiffRenderer renderer;
    private final Deque<String> log = new ArrayDeque<>();
    private Snapshot snapshot;
    private Page page = Page.BRIDGE;
    private int selection;
    private boolean detail;
    private boolean running = true;
    private String status = "READY";
    private String notice = "";
    private List<String> helpLines = List.of();
    private volatile boolean viewOnly;
    private volatile boolean dirty;
    private boolean gameQuit;

    public TuiApp(TuiGame game, Terminal terminal) {
        if (game == null || terminal == null) {
            throw new IllegalArgumentException("游戏与终端不能为空");
        }
        this.game = game;
        this.terminal = terminal;
        this.chars = terminal.unicode() ? CharSet.unicodeChars() : CharSet.asciiChars();
        this.renderer = new DiffRenderer(terminal.color());
        this.snapshot = SnapshotBuilder.build(game);
    }

    /** 交互式运行，直到输入 EOF 或 quit。 */
    public void run() {
        run(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
    }

    /**
     * 从已有 Reader 运行：CLI 内 tui on 时复用 REPL 的 BufferedReader，
     * 避免两个读取器互相抢缓冲输入。
     */
    public void run(BufferedReader reader) {
        terminal.enter();
        try {
            draw();
            while (running) {
                String line;
                try {
                    line = reader.readLine();
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "终端输入失败", e);
                    appendLog("输入失败：" + e.getMessage());
                    break;
                }
                if (line == null) {
                    break;
                }
                handle(line);
                if (!running) {
                    break;
                }
                if (terminal instanceof SystemTerminal system) {
                    system.refreshSize();
                }
                draw();
            }
        } finally {
            // 不关闭 System.in：TUI 可以从 CLI REPL 内进入，退出后 REPL 还要继续读。
            terminal.exit();
        }
    }

    /**
     * 处理一行输入；返回是否仍在运行。
     *
     * <p>不引入单字母缩写，也不新增 screen 之类的导航语法。map/ships/colonies/
     * research/market/log/status/decisions/help 直接复用 CLI 命令：先执行命令
     * （双窗口下由独立 CLI 窗口显示完整输出），再切换到对应可视化页面。
     * select/inspect/back 是列表
     * 操作，quit/exit 退出，其余整行原样交给游戏。</p>
     */
    public boolean handle(String rawLine) {
        if (rawLine == null) {
            running = false;
            return false;
        }
        String line = rawLine.strip();
        if (line.isEmpty()) {
            return running;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        if ("tui off".equals(lower) || "tui".equals(lower)) {
            running = false;
            gameQuit = false;
            return false;
        }
        if ("tui on".equals(lower)) {
            status = "已在 TUI 中";
            return running;
        }
        switch (lower) {
            case "quit", "exit" -> {
                running = false;
                gameQuit = true;
                return false;
            }
            case "back" -> back();
            case "inspect" -> openDetail();
            default -> {
                if (PAGE_COMMANDS.contains(lower)) {
                    navigate(lower);
                } else if (lower.startsWith("select")) {
                    select(line);
                } else {
                    executeCommand(line);
                }
            }
        }
        // 行输入回显发生在终端里，差量渲染器看不到；每次提交后强制全量重绘，
        // 同时消除终端换行/尺寸差异导致的旧页面残留。
        renderer.reset();
        return running;
    }

    /** 页面命令：执行 CLI 命令，然后切换到可视化页面。 */
    private void navigate(String command) {
        String output = executeCommand(command);
        switch (command) {
            case "map" -> page = Page.MAP;
            case "ships" -> page = Page.FLEET;
            case "colonies" -> page = Page.COLONY;
            case "research" -> page = Page.RESEARCH;
            case "market" -> page = Page.TRADE;
            case "log", "decisions" -> page = Page.INTEL;
            case "help" -> {
                page = Page.HELP;
                helpLines = output == null ? List.of()
                        : List.of(output.replace("\r\n", "\n").split("\n", -1));
            }
            case "status" -> page = Page.BRIDGE;
            default -> {
            }
        }
        selection = 0;
        detail = false;
    }

    /** 双窗口模式下 TUI 只读：命令由独立 CLI 窗口输入。 */
    public void setViewOnly(boolean viewOnly) {
        this.viewOnly = viewOnly;
    }

    /** CLI 窗口执行完命令后通知 TUI：刷新快照，并在是页面命令时切换视图。 */
    public void externalUpdate(String command) {
        String head = head(command == null ? "" : command);
        switch (head) {
            case "map" -> page = Page.MAP;
            case "ships" -> page = Page.FLEET;
            case "colonies" -> page = Page.COLONY;
            case "research" -> page = Page.RESEARCH;
            case "market" -> page = Page.TRADE;
            case "log", "decisions" -> page = Page.INTEL;
            case "help" -> page = Page.HELP;
            case "status" -> page = Page.BRIDGE;
            default -> {
            }
        }
        if (PAGE_COMMANDS.contains(head)) {
            selection = 0;
            detail = false;
        }
        dirty = true;
    }

    /** 请求 TUI 退出（双窗口模式下由 CLI 的 quit 或宿主关闭触发）。 */
    public void requestStop() {
        running = false;
        dirty = true;
    }

    /**
     * 只读可视化循环：不读标准输入，按宿主事件刷新画面。
     * 用于 TUI + 独立 CLI 窗口模式。
     */
    public void runViewOnly() {
        terminal.enter();
        terminal.write(Ansi.CURSOR_HIDE);
        try {
            draw();
            while (running) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (dirty) {
                    dirty = false;
                    refresh();
                    renderer.reset();
                    draw();
                }
            }
        } finally {
            terminal.exit();
        }
    }

    /** 生成当前帧画布（测试与渲染器共用）。 */
    public ScreenBuffer frame() {
        TerminalSize size = terminal.size();
        ScreenBuffer buffer = new ScreenBuffer(size.columns(), size.rows());
        compose(buffer, size);
        return buffer;
    }

    /** 组合当前帧并返回差量输出。 */
    public String renderFrame() {
        return renderer.render(frame());
    }

    /** 把当前状态画到终端，并把光标放到命令输入处。 */
    public void draw() {
        ScreenBuffer frame = frame();
        terminal.write(renderer.render(frame));
        if (viewOnly) {
            terminal.moveCursor(terminal.size().rows(), 1);
        } else {
            // 命令行在倒数第二行（0 基 rows-2），提示文本从第 2 列开始。
            terminal.moveCursor(Math.max(1, terminal.size().rows() - 1),
                    PROMPT.length() + 2);
        }
    }

    public Page page() {
        return page;
    }

    public int selection() {
        return selection;
    }

    public boolean detailOpen() {
        return detail;
    }

    public boolean running() {
        return running;
    }

    /** TUI 是否因 quit/exit 请求结束整个游戏；tui off 不算。 */
    public boolean gameQuit() {
        return gameQuit;
    }

    public String status() {
        return status;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    @Override
    public void close() {
        terminal.exit();
    }

    private void switchPage(Page next) {
        page = next;
        selection = 0;
        detail = false;
        status = switch (next) {
            case BRIDGE -> "舰桥态势总览";
            case MAP -> "星域导航";
            case FLEET -> "舰队管理";
            case COLONY -> "殖民地管理";
            case RESEARCH -> "科研中心";
            case TRADE -> "贸易中心";
            case INTEL -> "情报中心";
            case HELP -> "帮助";
        };
    }

    private void back() {
        if (detail) {
            detail = false;
        } else if (page != Page.BRIDGE) {
            switchPage(Page.BRIDGE);
        }
    }

    /** select <编号|up|down>：完整单词的列表操作，避免与 CLI 命令冲突。 */
    private void select(String line) {
        int count = selectableCount();
        if (count <= 0) {
            status = "当前页面没有可选项目";
            return;
        }
        String[] parts = line.strip().split("\\s+");
        if (parts.length != 2) {
            status = "用法：select <编号|up|down>";
            return;
        }
        String argument = parts[1].toLowerCase(Locale.ROOT);
        if ("up".equals(argument)) {
            selection = Math.max(0, selection - 1);
        } else if ("down".equals(argument)) {
            selection = Math.min(count - 1, selection + 1);
        } else {
            try {
                selection = Math.max(0, Math.min(count - 1,
                        Integer.parseInt(argument) - 1));
            } catch (NumberFormatException e) {
                status = "select 需要编号或 up/down：" + parts[1];
                return;
            }
        }
        status = "选中 " + (selection + 1) + "/" + count;
    }

    private void openDetail() {
        if (selectableCount() > 0) {
            detail = true;
        } else {
            status = "当前页面没有可查看的详情";
        }
    }

    private String executeCommand(String command) {
        String output = "";
        try {
            output = game.execute(command);
            appendLog("> " + command);
            if (output != null && !output.isBlank()) {
                appendOutput(output);
            }
            if ("bye".equals(output)) {
                running = false;
            }
            refresh();
            if (head(command).equals("next") && !snapshot.decisions().isEmpty()) {
                blockedNotice();
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "命令执行失败: " + command, e);
            status = "命令失败：" + e.getMessage();
            appendLog(status);
        }
        return output;
    }

    /** 时间被待决事项阻塞时，记下原因与可执行路径并切到情报页。 */
    private void blockedNotice() {
        page = Page.INTEL;
        selection = 0;
        detail = false;
        appendLog("! 时间推进被 " + snapshot.decisions().size()
                + " 项待决事项阻塞（这是世界规则，不是故障）");
        appendLog("  处理：decisions 查看完整方案；decide <决策id> <选项id> 结算后再 next");
        int shown = 0;
        for (Snapshot.DecisionView decision : snapshot.decisions()) {
            if (shown++ >= 2) {
                break;
            }
            List<String> options = new ArrayList<>();
            for (Snapshot.OptionView option : decision.options()) {
                options.add(option.id() + "(" + option.label() + ")");
            }
            appendLog("  [" + decision.id() + "] " + decision.title() + "："
                    + String.join(" / ", options));
        }
    }

    private void refresh() {
        try {
            synchronized (game.lock()) {
                snapshot = SnapshotBuilder.build(game);
            }
            notice = snapshot.decisions().isEmpty() ? ""
                    : "! 有 " + snapshot.decisions().size()
                            + " 项待办：时间推进被阻塞；decisions 查看，decide <id> <选项>";
            int count = selectableCount();
            if (count <= 0) {
                selection = 0;
                detail = false;
            } else {
                selection = Math.max(0, Math.min(count - 1, selection));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "快照构建失败", e);
            appendLog("快照构建失败：" + e.getMessage());
        }
    }

    private void appendOutput(String output) {
        for (String line : output.replace("\r\n", "\n").split("\n")) {
            appendLog(line);
        }
    }

    private void appendLog(String line) {
        if (line == null) {
            return;
        }
        String trimmed = line.stripTrailing();
        if (trimmed.isBlank()) {
            return;
        }
        log.addLast(trimmed);
        while (log.size() > 500) {
            log.removeFirst();
        }
        status = trimmed;
    }

    private void compose(ScreenBuffer buffer, TerminalSize size) {
        int width = size.columns();
        int height = size.rows();
        buffer.clear(Style.DEFAULT);
        drawHeader(buffer, width);

        int bodyTop = 3;
        int bodyBottom = height - 6;
        Rect body = new Rect(1, bodyTop, Math.max(0, width - 2),
                Math.max(0, bodyBottom - bodyTop));
        if (body.height() > 0 && body.width() > 0) {
            switch (page) {
                case BRIDGE -> BridgeView.render(buffer, body, snapshot, chars);
                case MAP -> MapView.render(buffer, body, snapshot, selection, chars);
                case FLEET -> FleetView.render(buffer, body, snapshot, selection, chars);
                case COLONY -> ColonyView.render(buffer, body, snapshot, selection, chars);
                case RESEARCH -> ResearchView.render(buffer, body, snapshot, chars);
                case TRADE -> TradeView.render(buffer, body, snapshot, chars);
                case INTEL -> IntelView.render(buffer, body, snapshot, selection, chars);
                case HELP -> HelpView.render(buffer, body, helpLines, chars);
            }
        }
        if (detail) {
            drawDetail(buffer, body);
        }
        drawOrganization(buffer, width, height - 5);
        drawLog(buffer, width, height - 4);
        drawKeys(buffer, width, height - 3);
        drawPrompt(buffer, width, height - 2);
    }

    private void drawHeader(ScreenBuffer buffer, int width) {
        buffer.put(0, 0, chars.doubleTopLeft(), Style.ACCENT);
        buffer.horizontal(0, 1, Math.max(0, width - 2), chars.doubleHorizontal(),
                Style.ACCENT);
        buffer.put(width - 1, 0, chars.doubleTopRight(), Style.ACCENT);
        buffer.put(0, 1, chars.vertical(), Style.ACCENT);
        buffer.put(width - 1, 1, chars.vertical(), Style.ACCENT);

        String title = " EXPEDITION COMMAND TERMINAL ";
        buffer.putClipped(1, 1, title, Style.ACCENT, width - 2);
        String right = "STARDATE " + snapshot.date() + "   CYCLE "
                + String.format(Locale.ROOT, "%05d", snapshot.day())
                + "   " + chars.bullet() + " SYSTEM ONLINE";
        int rightWidth = Math.max(0, width - 2 - TextWidth.width(title) - 1);
        int rightX = width - 1 - TextWidth.width(right);
        if (rightWidth > 0 && rightX > 1 + TextWidth.width(title)) {
            buffer.putClipped(rightX, 1, right, Style.PANEL_OK, rightWidth);
        } else {
            String compact = "SD " + snapshot.date() + " · D" + snapshot.day();
            int x = Math.max(1, width - 2 - TextWidth.width(compact));
            if (x > 1 + TextWidth.width(title)) {
                buffer.putClipped(x, 1, compact, Style.MUTED, width - 1 - x);
            }
        }
        buffer.put(0, 2, chars.vertical(), Style.ACCENT);
        buffer.put(width - 1, 2, chars.vertical(), Style.ACCENT);
        buffer.horizontal(2, 1, Math.max(0, width - 2), chars.doubleHorizontal(),
                Style.MUTED);
    }

    private void drawOrganization(ScreenBuffer buffer, int width, int y) {
        if (y < 0 || y >= buffer.height()) {
            return;
        }
        buffer.fill(new Rect(0, y, width, 1), Style.PANEL);
        String text = "CREDITS " + snapshot.credits()
                + "   " + chars.bullet() + " FUEL " + amount(fc.world.ResourceKind.FUEL)
                + "   " + chars.bullet() + " FOOD " + amount(fc.world.ResourceKind.FOOD)
                + "   " + chars.bullet() + " COLONIES " + snapshot.colonies().size()
                + "   " + chars.bullet() + " REPUTATION " + maxReputation()
                + (snapshot.ending().isBlank() ? "" : "   " + chars.bullet()
                        + " ENDING " + snapshot.ending());
        buffer.putClipped(1, y, text, Style.HEADER, Math.max(0, width - 2));
        if (!snapshot.decisions().isEmpty()) {
            int used = 1 + TextWidth.width(TextWidth.truncate(text, width - 2));
            String alert = chars.warn() + " 待办 " + snapshot.decisions().size()
                    + "：decisions / decide";
            if (used < width - 2) {
                buffer.putClipped(used, y, alert, Style.WARN, width - 1 - used);
            }
        }
    }

    private void drawLog(ScreenBuffer buffer, int width, int y) {
        if (y < 0 || y >= buffer.height()) {
            return;
        }
        String line = notice.isBlank() ? status : notice;
        buffer.putClipped(1, y, TextWidth.truncate(line, Math.max(0, width - 2)),
                notice.isBlank() ? Style.MUTED : Style.WARN, Math.max(0, width - 2));
    }

    private void drawKeys(ScreenBuffer buffer, int width, int y) {
        if (y < 0 || y >= buffer.height()) {
            return;
        }
        String keys = viewOnly
                ? "在 CLI 窗口输入 map/ships/colonies/research/market/log/status/decisions/help 切换视图"
                : "页面 map ships colonies research market log status decisions help | "
                        + "列表 select N/up/down inspect back | next N | quit";
        buffer.putClipped(1, y, TextWidth.truncate(keys, Math.max(0, width - 2)),
                Style.MUTED, Math.max(0, width - 2));
    }

    private void drawPrompt(ScreenBuffer buffer, int width, int y) {
        if (y < 0 || y >= buffer.height() || width < PROMPT.length()) {
            return;
        }
        buffer.fill(new Rect(0, y, width, 1), Style.PANEL);
        String prompt = viewOnly
                ? "CLI WINDOW > 命令请在独立命令行窗口输入；此窗口只做可视化。"
                : PROMPT;
        buffer.putClipped(1, y, prompt, Style.ACCENT, width - 1);
        int bottom = buffer.height() - 1;
        buffer.put(0, bottom, chars.doubleBottomLeft(), Style.ACCENT);
        buffer.horizontal(bottom, 1, Math.max(0, width - 2),
                chars.doubleHorizontal(), Style.MUTED);
        buffer.put(width - 1, bottom, chars.doubleBottomRight(), Style.ACCENT);
    }

    private void drawDetail(ScreenBuffer buffer, Rect body) {
        if (body.width() < 20 || body.height() < 6) {
            return;
        }
        int width = Math.min(body.width() - 4, Math.max(24, body.width() * 3 / 4));
        int height = Math.min(body.height() - 2, 14);
        int x = body.x() + (body.width() - width) / 2;
        int y = body.y() + (body.height() - height) / 2;
        Rect rect = new Rect(x, y, width, height);
        buffer.fill(rect, Style.PANEL);
        buffer.box(rect, "DETAIL / 详情", Style.ACCENT, Style.PANEL_ACCENT, chars);
        Rect inner = rect.inset(2, 2);
        List<String> lines = Draw.wrap(detailText(), inner.width());
        for (int i = 0; i < lines.size() && i < inner.height() - 1; i++) {
            Draw.line(buffer, inner, i, lines.get(i), Style.PANEL);
        }
        Draw.line(buffer, inner, inner.height() - 1, "[B] 关闭", Style.PANEL_MUTED);
    }

    private String detailText() {
        return switch (page) {
            case MAP -> MapView.detail(snapshot, selection);
            case FLEET -> FleetView.detail(snapshot, selection);
            case COLONY -> ColonyView.detail(snapshot, selection);
            case INTEL -> IntelView.detail(snapshot, selection);
            default -> "此页面没有详情。";
        };
    }

    private int selectableCount() {
        return switch (page) {
            case MAP -> MapView.count(snapshot);
            case FLEET -> FleetView.count(snapshot);
            case COLONY -> ColonyView.count(snapshot);
            case INTEL -> IntelView.count(snapshot);
            default -> 0;
        };
    }

    private String amount(fc.world.ResourceKind kind) {
        java.math.BigDecimal value = snapshot.playerResources().get(kind);
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private int maxReputation() {
        int max = 0;
        boolean first = true;
        for (Snapshot.FactionView faction : snapshot.factions()) {
            if (first) {
                max = faction.reputation();
                first = false;
            } else {
                max = Math.max(max, faction.reputation());
            }
        }
        return max;
    }

    private static String head(String command) {
        String stripped = command.strip();
        int space = stripped.indexOf(' ');
        return (space < 0 ? stripped : stripped.substring(0, space)).toLowerCase(Locale.ROOT);
    }
}
