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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 星际远征指挥终端：全屏、分区、键盘驱动的终端客户端。
 *
 * <p>渲染走 {@link ScreenBuffer} + {@link DiffRenderer}；页面只读 {@link Snapshot}；
 * 所有不可逆/世界推进操作都通过 {@link TuiGame#execute(String)} 进入游戏应用层。
 * 界面刷新与游戏时间分离：渲染循环从不推进 Tick，只有明确的 {@code n}/{@code n5}
 * 或命令行 {@code next} 才推进世界。</p>
 *
 * <p>输入采用标准输入逐行读取（Java SE 21 无跨平台 Raw Mode）。
 * 单字符行是快捷键；其余整行作为游戏命令交给 CLI。按空格行推进 1 天。</p>
 */
public final class TuiApp implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(TuiApp.class.getName());
    private static final String PROMPT = "COMMAND > ";

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

    /** 交互式运行，直到输入 EOF 或按 q。 */
    public void run() {
        terminal.enter();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
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
            try {
                reader.close();
            } catch (IOException ignored) {
                // 标准输入关闭失败不影响终端恢复。
            }
            terminal.exit();
        }
    }

    /**
     * 处理一行输入；返回是否仍在运行。
     *
     * <p>行输入模式不引入单字母缩写，也不占用 CLI 已有命令名：页面切换使用
     * {@code screen <页面>}（bridge/map/fleet/colony/research/trade/intel/help），
     * 列表操作使用 select/inspect/back，退出使用 quit/exit。其余整行原样交给
     * 游戏命令，因此 {@code help}、{@code map}、{@code market} 等仍保持 CLI 语义。</p>
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
        if (lower.startsWith("screen")) {
            screen(line);
        } else {
            switch (lower) {
                case "quit", "exit" -> {
                    running = false;
                    return false;
                }
                case "back" -> back();
                case "inspect" -> openDetail();
                default -> {
                    if (lower.startsWith("select")) {
                        select(line);
                    } else {
                        executeCommand(line);
                    }
                }
            }
        }
        // 行输入回显发生在终端里，差量渲染器看不到；每次提交后强制全量重绘，
        // 同时消除终端换行/尺寸差异导致的旧页面残留。
        renderer.reset();
        return running;
    }

    /** screen &lt;页面&gt;：明确的 TUI 导航命名空间，不改变任何 CLI 命令语义。 */
    private void screen(String line) {
        String[] parts = line.strip().split("\\s+");
        if (parts.length != 2) {
            status = "用法：screen <bridge|map|fleet|colony|research|trade|intel|help>";
            return;
        }
        switch (parts[1].toLowerCase(Locale.ROOT)) {
            case "bridge" -> switchPage(Page.BRIDGE);
            case "map" -> switchPage(Page.MAP);
            case "fleet" -> switchPage(Page.FLEET);
            case "colony" -> switchPage(Page.COLONY);
            case "research" -> switchPage(Page.RESEARCH);
            case "trade" -> switchPage(Page.TRADE);
            case "intel" -> switchPage(Page.INTEL);
            case "help" -> switchPage(Page.HELP);
            default -> status = "未知页面：" + parts[1]
                    + "（bridge/map/fleet/colony/research/trade/intel/help）";
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
        // 命令行在倒数第二行（0 基 rows-2），提示文本从第 2 列开始。
        terminal.moveCursor(Math.max(1, terminal.size().rows() - 1), PROMPT.length() + 2);
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

    private void executeCommand(String command) {
        try {
            String output = game.execute(command);
            appendLog("> " + command);
            if (output != null && !output.isBlank()) {
                appendOutput(output);
            }
            if ("bye".equals(output)) {
                running = false;
            }
            refresh();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "命令执行失败: " + command, e);
            appendLog("命令失败：" + e.getMessage());
            status = "命令失败：" + e.getMessage();
        }
    }

    private void refresh() {
        try {
            snapshot = SnapshotBuilder.build(game);
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
        while (log.size() > 200) {
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
                case HELP -> HelpView.render(buffer, body, chars);
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
    }

    private void drawLog(ScreenBuffer buffer, int width, int y) {
        if (y < 0 || y >= buffer.height()) {
            return;
        }
        buffer.putClipped(1, y, TextWidth.truncate(status, Math.max(0, width - 2)),
                Style.MUTED, Math.max(0, width - 2));
    }

    private void drawKeys(ScreenBuffer buffer, int width, int y) {
        if (y < 0 || y >= buffer.height()) {
            return;
        }
        String keys = "screen bridge/map/fleet/colony/research/trade/intel/help | "
                + "select N/up/down  inspect  back | next N | quit";
        buffer.putClipped(1, y, TextWidth.truncate(keys, Math.max(0, width - 2)),
                Style.MUTED, Math.max(0, width - 2));
    }

    private void drawPrompt(ScreenBuffer buffer, int width, int y) {
        if (y < 0 || y >= buffer.height() || width < PROMPT.length()) {
            return;
        }
        buffer.fill(new Rect(0, y, width, 1), Style.PANEL);
        buffer.putClipped(1, y, PROMPT, Style.ACCENT, width - 1);
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
}
