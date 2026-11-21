package fc.tui;

/** 帮助页：页面命令、列表操作与常用游戏命令。 */
public final class HelpView {

    private static final String[][] PAGES = {
            {"screen bridge", "舰桥总览"}, {"screen map", "星域导航"},
            {"screen fleet", "舰队管理"}, {"screen colony", "殖民地"},
            {"screen research", "科研中心"}, {"screen trade", "贸易中心"},
            {"screen intel", "情报中心"}, {"screen help", "本页"}
    };

    private static final String[] UI = {
            "screen <页面>       切换页面（见左栏）",
            "select <编号>       选中列表第 N 项（1 起）",
            "select up/down      上/下移动选择",
            "inspect             打开选中项详情",
            "back                关闭详情；再退到舰桥总览",
            "next <天数>         推进世界（不做隐式推进）",
            "quit / exit         退出指挥终端"
    };

    private static final String[] COMMANDS = {
            "sail <船> <地点>        启航",
            "survey <地点> <船>      勘察",
            "research start <科技> <实验室>",
            "colonies upgrade <地点>",
            "market / trade ...      市场与交易",
            "save <槽位> [口令]      存档（由运行时适配器处理）",
            "help                    完整 CLI 命令列表"
    };

    private HelpView() {
    }

    public static void render(ScreenBuffer buffer, Rect body, CharSet chars) {
        int leftWidth = Math.max(36, body.width() * 46 / 100);
        Rect[] columns = body.splitColumns(leftWidth);
        Rect pages = Draw.panel(buffer, columns[0], "页面命令 / SCREENS", chars);
        Rect right = columns[1];
        Rect[] halves = right.splitRows(Math.max(10, right.height() * 52 / 100));
        Rect ui = Draw.panel(buffer, halves[0], "列表与终端操作", chars);
        Rect commands = Draw.panel(buffer, halves[1], "常用游戏命令", chars);

        int row = 0;
        for (String[] page : PAGES) {
            if (row >= pages.height() - 2) {
                break;
            }
            Draw.line(buffer, pages, row++, TextWidth.padRight(page[0], 12) + page[1],
                    Style.PANEL_MUTED);
        }
        if (pages.height() > 3) {
            Draw.line(buffer, pages, pages.height() - 2,
                    "输入完整命令；行模式需要回车提交。", Style.PANEL_MUTED);
            Draw.line(buffer, pages, pages.height() - 1,
                    "不使用单字母缩写，行为与 CLI 命令名一致。", Style.PANEL_WARN);
        }

        row = 0;
        for (String line : UI) {
            if (row >= ui.height()) {
                break;
            }
            Draw.line(buffer, ui, row++, line, Style.PANEL);
        }

        row = 0;
        for (String line : COMMANDS) {
            if (row >= commands.height()) {
                break;
            }
            Draw.line(buffer, commands, row++, line, Style.PANEL);
        }
    }
}
