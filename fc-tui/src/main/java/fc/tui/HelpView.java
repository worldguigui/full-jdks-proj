package fc.tui;

/** 帮助页：快捷键、页面说明与常用命令。 */
public final class HelpView {

    private static final String[][] KEYS = {
            {"M", "星图导航"}, {"F", "舰队管理"}, {"C", "殖民地"},
            {"R", "科研中心"}, {"T", "贸易中心"}, {"E", "情报中心"},
            {"B / ?", "返回总览/帮助"}, {"J K", "上下选择"},
            {"I", "详情弹窗"}, {"N", "推进 1 天（n5 = 推进 5 天）"},
            {"空格 + 回车", "推进 1 天（行输入模式）"}, {"Q", "退出"}
    };

    private static final String[] COMMANDS = {
            "next 10                推进 10 天",
            "status                 世界概况",
            "market                 市场报价",
            "sail <船> <地点>        启航",
            "survey <地点> <船>      勘察",
            "research start <科技> <实验室>",
            "colony upgrade <地点>",
            "trade buy fuel 20      买入货物",
            "save <槽位> [口令]      存档",
            "help                   完整命令列表"
    };

    private HelpView() {
    }

    public static void render(ScreenBuffer buffer, Rect body, CharSet chars) {
        int leftWidth = Math.max(34, body.width() * 45 / 100);
        Rect[] columns = body.splitColumns(leftWidth);
        Rect keys = Draw.panel(buffer, columns[0], "快捷键 / KEY BINDINGS", chars);
        Rect commands = Draw.panel(buffer, columns[1], "常用命令 / APPLICATIONS", chars);

        int row = 0;
        for (String[] key : KEYS) {
            if (row >= keys.height()) {
                break;
            }
            Draw.line(buffer, keys, row++, TextWidth.padRight(key[0], 12) + key[1],
                    Style.PANEL_MUTED);
        }

        int commandRow = 0;
        for (String command : COMMANDS) {
            if (commandRow >= commands.height()) {
                break;
            }
            Draw.line(buffer, commands, commandRow++, command, Style.PANEL);
        }
        if (commands.height() > COMMANDS.length + 1) {
            Draw.line(buffer, commands, commands.height() - 2,
                    "TUI 与 CLI 共用同一套命令；界面不会自动推进世界。",
                    Style.PANEL_MUTED);
            Draw.line(buffer, commands, commands.height() - 1,
                    "图形终端可用；行输入模式需要按回车提交。", Style.PANEL_MUTED);
        }
    }
}
