package fc.tui;

import fc.history.InMemoryChronicle;
import fc.testkit.FcTest;
import fc.testkit.TestRunner;
import fc.world.ColonyState;
import fc.world.Ids;
import fc.world.KnowledgeState;
import fc.world.Link;
import fc.world.Location;
import fc.world.LocationGraph;
import fc.world.ResourceKind;
import fc.world.ResourceLedger;
import fc.world.Stock;
import fc.world.WorldState;
import fc.world.time.Stardate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 终端渲染、宽度、差分刷新、页面切换、命令桥接与尺寸变化。 */
public final class TuiEngineTest {

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(TuiEngineTest.class))) {
            System.exit(1);
        }
    }

    // ---- 测试夹具 ----

    private static WorldState world() {
        Ids.LocationId system = new Ids.LocationId(1);
        Ids.LocationId planet = new Ids.LocationId(2);
        Ids.LocationId colonyId = new Ids.LocationId(3);
        Ids.LocationId shipId = new Ids.LocationId(4);
        Map<Ids.LocationId, Location> nodes = new LinkedHashMap<>();
        nodes.put(system, new Location.StarSystem(system, Stock.empty(), "晨曦"));
        nodes.put(planet, new Location.Planet(planet, Stock.empty(), "新伊甸"));
        nodes.put(colonyId, new Location.Colony(colonyId, Stock.empty(), "先驱前哨"));
        Location.Ship ship = new Location.Ship(shipId, "先驱号",
                new Ids.ShipId(100), system,
                Stock.of(Map.of(ResourceKind.FUEL, new BigDecimal("40"))),
                new Location.VoyageProgress(system, planet, 1, 3, Stardate.EPOCH));
        nodes.put(shipId, ship);
        LocationGraph graph = new LocationGraph(nodes, List.of(
                new Link.WarpRoute(system, planet, 3, 0.1, new BigDecimal("5")),
                new Link.WarpRoute(planet, colonyId, 2, 0.0, new BigDecimal("2"))));
        WorldState state = new WorldState(graph, Map.of(), new ResourceLedger(),
                new InMemoryChronicle(), new Stardate(12));
        state.setKnowledge(system, KnowledgeState.CHARTED);
        state.setKnowledge(planet, KnowledgeState.SURVEYED);
        state.setKnowledge(colonyId, KnowledgeState.DETECTED);
        state.addColony(new ColonyState(colonyId, true, 1, 120, List.of(
                new ColonyState.Upgrade(2,
                        Map.of(ResourceKind.FOOD, new BigDecimal("10")),
                        new BigDecimal("200")))));
        state.setStock(system,
                Stock.of(Map.of(ResourceKind.FOOD, new BigDecimal("300"),
                        ResourceKind.FUEL, new BigDecimal("120"))), "test");
        state.depositPlayer(new BigDecimal("1500"), "test");
        return state;
    }

    private static final class FakeGame implements TuiGame {
        final WorldState state = world();
        final List<String> commands = new ArrayList<>();
        boolean fail;

        @Override
        public WorldState state() {
            return state;
        }

        @Override
        public String execute(String command) {
            if (fail && command.equals("boom")) {
                throw new IllegalStateException("模拟故障");
            }
            commands.add(command);
            return "已执行 " + command;
        }

        @Override
        public String marketReport() {
            return "燃料 12 / 食物 8";
        }

        @Override
        public String researchReport() {
            return "科研进度 0%";
        }

        @Override
        public String technologiesReport() {
            return "科技 0 项";
        }
    }

    private static final class FakeTerminal implements Terminal {
        private TerminalSize size;
        private final boolean unicode;
        private final StringBuilder written = new StringBuilder();
        int enters;
        int exits;

        FakeTerminal(TerminalSize size, boolean unicode) {
            this.size = size;
            this.unicode = unicode;
        }

        void setSize(TerminalSize next) {
            size = next;
        }

        @Override
        public TerminalSize size() {
            return size;
        }

        @Override
        public boolean unicode() {
            return unicode;
        }

        @Override
        public boolean color() {
            return true;
        }

        @Override
        public void enter() {
            enters++;
        }

        @Override
        public void exit() {
            exits++;
        }

        @Override
        public void write(String text) {
            written.append(text);
        }

        @Override
        public void flush() {
        }
    }

    private static String text(ScreenBuffer buffer) {
        StringBuilder out = new StringBuilder();
        for (int y = 0; y < buffer.height(); y++) {
            out.append(buffer.row(y)).append('\n');
        }
        return out.toString();
    }

    private static void assertRowsHaveWidth(ScreenBuffer buffer) {
        for (int y = 0; y < buffer.height(); y++) {
            assert TextWidth.width(buffer.row(y)) == buffer.width()
                    : "第 " + y + " 行宽度 " + TextWidth.width(buffer.row(y));
        }
    }

    // ---- 用例 ----


    /** 极简 ANSI 终端模拟器：只处理定位、清屏与文本，用于验证差量输出。 */
    private static final class AnsiScreen {
        private final int width;
        private final int height;
        private final int[] cells;
        private final boolean[] continuation;
        private int row;
        private int column;

        AnsiScreen(int width, int height) {
            this.width = width;
            this.height = height;
            this.cells = new int[width * height];
            this.continuation = new boolean[width * height];
            for (int i = 0; i < cells.length; i++) {
                cells[i] = ' ';
            }
        }

        void moveTo(int newRow, int newColumn) {
            row = newRow;
            column = newColumn;
        }

        void apply(String output) {
            for (int i = 0; i < output.length(); ) {
                char ch = output.charAt(i);
                if (ch == 27) {
                    int start = i + 2;
                    int end = start;
                    while (end < output.length()
                            && !Character.isLetter(output.charAt(end))) {
                        end++;
                    }
                    String params = output.substring(start, end);
                    char command = end < output.length() ? output.charAt(end) : ' ';
                    if (command == 'H') {
                        String[] parts = params.split(";");
                        if (parts.length == 2) {
                            row = Math.max(1, Integer.parseInt(parts[0])) - 1;
                            column = Math.max(1, Integer.parseInt(parts[1])) - 1;
                        }
                    } else if (command == 'J' && params.contains("2")) {
                        for (int k = 0; k < cells.length; k++) {
                            cells[k] = ' ';
                            continuation[k] = false;
                        }
                    }
                    i = end + 1;
                    continue;
                }
                int cp = output.codePointAt(i);
                i += Character.charCount(cp);
                int w = Math.max(1, TextWidth.of(cp));
                if (row >= 0 && row < height && column >= 0 && column + w <= width) {
                    int at = row * width + column;
                    cells[at] = cp;
                    continuation[at] = false;
                    if (w == 2) {
                        cells[at + 1] = ' ';
                        continuation[at + 1] = true;
                    }
                    column += w;
                }
            }
        }

        String row(int y) {
            StringBuilder out = new StringBuilder();
            for (int x = 0; x < width; x++) {
                int at = y * width + x;
                if (!continuation[at]) {
                    out.appendCodePoint(cells[at]);
                }
            }
            return out.toString();
        }
    }

    @FcTest
    public void renderedAnsiOutputReconstructsTheCanvas() {
        FakeGame game = new FakeGame();
        TuiApp app = new TuiApp(game, new FakeTerminal(new TerminalSize(100, 30), true));
        AnsiScreen screen = new AnsiScreen(100, 30);
        screen.apply(app.renderFrame());
        ScreenBuffer first = app.frame();
        for (int y = 0; y < 30; y++) {
            assert screen.row(y).equals(first.row(y)) : "首帧第 " + y + " 行不一致";
        }

        app.handle("screen map");
        app.handle("select down");
        screen.apply(app.renderFrame());
        ScreenBuffer second = app.frame();
        for (int y = 0; y < 30; y++) {
            assert screen.row(y).equals(second.row(y)) : "差量帧第 " + y + " 行不一致";
        }
    }

    @FcTest
    public void initialFrameRendersBridgeRegions() {
        FakeGame game = new FakeGame();
        TuiApp app = new TuiApp(game, new FakeTerminal(new TerminalSize(120, 34), true));
        ScreenBuffer frame = app.frame();
        String text = text(frame);
        assert text.contains("EXPEDITION COMMAND TERMINAL") : text;
        assert text.contains("战略星图") : text;
        assert text.contains("先驱号") : text;
        assert text.contains("先驱前哨") : text;
        assert text.contains("CREDITS 1500") : text;
        assert text.contains("STARDATE") : text;
        assertRowsHaveWidth(frame);
    }

    @FcTest
    public void pageNavigationSelectionAndDetail() {
        FakeGame game = new FakeGame();
        TuiApp app = new TuiApp(game, new FakeTerminal(new TerminalSize(120, 34), true));

        assert app.handle("screen map") : "map 应保持运行";
        assert app.page() == TuiApp.Page.MAP : "应切换到星图";
        assert app.handle("select down") : "select down 应保持运行";
        assert app.selection() == 1 : "选择应移动：" + app.selection();
        assert app.handle("inspect") : "inspect 应保持运行";
        assert app.detailOpen() : "inspect 应打开详情";
        assert text(app.frame()).contains("DETAIL / 详情") : "详情弹窗标题缺失";
        assert app.handle("back") : "back 应保持运行";
        assert !app.detailOpen() : "back 应关闭详情";
        assert app.handle("back") : "back 应保持运行";
        assert app.page() == TuiApp.Page.BRIDGE : "back 应返回总览";
        app.handle("quit");
        assert !app.running() : "quit 应退出";
    }

    @FcTest
    public void commandsGoThroughApplicationLayerAndRenderingNeverAdvancesWorld() {
        FakeGame game = new FakeGame();
        TuiApp app = new TuiApp(game, new FakeTerminal(new TerminalSize(110, 32), true));

        app.handle("next 3");
        assert game.commands.equals(List.of("next 3")) : game.commands;
        for (int i = 0; i < 5; i++) {
            app.frame();
        }
        assert game.commands.size() == 1 : "渲染不得执行命令：" + game.commands;

        app.handle("next 1");
        assert game.commands.get(game.commands.size() - 1).equals("next 1")
                : game.commands;
        assert game.commands.size() == 2 : game.commands;
    }

    @FcTest
    public void diffRendererEmitsOnlyChangesAndHandlesWideChars() {
        ScreenBuffer first = new ScreenBuffer(20, 5);
        DiffRenderer renderer = new DiffRenderer(true);
        String full = renderer.render(first);
        assert full.contains(Ansi.CLEAR) : "首帧应清屏";
        ScreenBuffer same = new ScreenBuffer(20, 5);
        assert renderer.render(same).isEmpty() : "无变化不应输出";

        same.put(1, 1, "X", Style.DEFAULT);
        String changed = renderer.render(same);
        assert changed.contains("X") : changed;
        assert changed.contains(Ansi.ESC) : changed;

        same.put(2, 2, "星", Style.DEFAULT);
        renderer.render(same);
        ScreenBuffer narrow = new ScreenBuffer(20, 5);
        narrow.put(2, 2, "A", Style.DEFAULT);
        String replaced = renderer.render(narrow);
        assert replaced.contains("A") : replaced;
    }

    @FcTest
    public void textWidthAndAsciiFallback() {
        assert TextWidth.width("星际") == 4 : TextWidth.width("星际");
        assert TextWidth.width("A中B") == 4 : TextWidth.width("A中B");
        assert TextWidth.width("́") == 0;
        assert TextWidth.width("🚀") == 2 : TextWidth.width("🚀");
        assert TextWidth.width(TextWidth.padRight("晨曦", 8)) == 8;
        assert TextWidth.width(TextWidth.truncate("星际远征指挥终端", 8)) <= 8;

        ScreenBuffer sanitized = new ScreenBuffer(12, 2);
        sanitized.put(0, 0, Character.toString(27) + "[2J", Style.DEFAULT);
        assert !sanitized.row(0).contains(Character.toString(27))
                : "画布不得输出控制字符";

        FakeGame game = new FakeGame();
        TuiApp app = new TuiApp(game, new FakeTerminal(new TerminalSize(100, 30), false));
        ScreenBuffer frame = app.frame();
        String text = text(frame);
        assert text.contains("+") : text;
        assert !text.contains("╔") : "ASCII 模式不得输出 Unicode 线框";
        assert !text.contains("★") : "ASCII 模式不得输出星形符号";
        assertRowsHaveWidth(frame);
    }

    @FcTest
    public void terminalResizeRebuildsLayout() {
        FakeGame game = new FakeGame();
        FakeTerminal terminal = new FakeTerminal(new TerminalSize(80, 24), true);
        TuiApp app = new TuiApp(game, terminal);
        ScreenBuffer small = app.frame();
        app.renderFrame(); // 以 80×24 建立上一帧
        assert small.width() == 80 && small.height() == 24;
        assert text(small).contains("EXPEDITION");
        assertRowsHaveWidth(small);

        terminal.setSize(new TerminalSize(120, 34));
        String resized = app.renderFrame();
        assert resized.contains(Ansi.CLEAR) : "尺寸变化必须全量重绘";
        ScreenBuffer large = app.frame();
        assert large.width() == 120 && large.height() == 34;
        assert text(large).contains("先驱号");
        assertRowsHaveWidth(large);
    }

    @FcTest
    public void pageSwitchDoesNotLeakPreviousContent() {
        FakeGame game = new FakeGame();
        TuiApp app = new TuiApp(game, new FakeTerminal(new TerminalSize(110, 32), true));
        String bridge = text(app.frame());
        assert bridge.contains("先驱号") : bridge;

        assert app.handle("screen help");
        String help = text(app.frame());
        assert help.contains("页面命令") : help;
        assert help.contains("select") : help;
        assert !help.contains("先驱号") : "帮助页不得残留总览内容";
        assert !help.contains("战略星图") : "帮助页不得残留星图内容";

        assert app.handle("screen map");
        String map = text(app.frame());
        assert map.contains("战略星图") : map;
        assert !map.contains("页面命令") : "星图页不得残留帮助内容";
    }

    @FcTest
    public void submittedLineEchoIsClearedByFullRedraw() {
        FakeGame game = new FakeGame();
        TuiApp app = new TuiApp(game, new FakeTerminal(new TerminalSize(100, 30), true));
        AnsiScreen screen = new AnsiScreen(100, 30);
        screen.apply(app.renderFrame());
        // 模拟行输入回显：光标在 COMMAND > 之后，终端把 "help" 直接写到屏幕上。
        screen.moveTo(28, 11);
        screen.apply("screen help");
        app.handle("screen help");
        screen.apply(app.renderFrame());
        ScreenBuffer expected = app.frame();
        for (int y = 0; y < 30; y++) {
            assert screen.row(y).equals(expected.row(y))
                    : "第 " + y + " 行残留输入回显或旧内容";
        }
    }

    @FcTest
    public void commandFailureIsReportedWithoutStoppingTheClient() {
        FakeGame game = new FakeGame();
        game.fail = true;
        TuiApp app = new TuiApp(game, new FakeTerminal(new TerminalSize(110, 32), true));
        assert app.handle("boom");
        assert app.running();
        assert app.status().contains("命令失败") : app.status();
        assert text(app.frame()).contains("模拟故障") : app.status();
    }
}
