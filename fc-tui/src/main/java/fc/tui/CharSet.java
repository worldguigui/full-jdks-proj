package fc.tui;

/**
 * 字符集抽象：Unicode 线框与 ASCII 降级。业务/页面代码只引用这里的符号，
 * 不直接散落 Unicode 字符，便于在旧终端上整体降级。
 */
public record CharSet(boolean unicode) {

    public static CharSet unicodeChars() {
        return new CharSet(true);
    }

    public static CharSet asciiChars() {
        return new CharSet(false);
    }

    public String topLeft() {
        return unicode ? "┌" : "+";
    }

    public String topRight() {
        return unicode ? "┐" : "+";
    }

    public String bottomLeft() {
        return unicode ? "└" : "+";
    }

    public String bottomRight() {
        return unicode ? "┘" : "+";
    }

    public String horizontal() {
        return unicode ? "─" : "-";
    }

    public String vertical() {
        return unicode ? "│" : "|";
    }

    public String doubleHorizontal() {
        return unicode ? "═" : "=";
    }

    public String doubleTopLeft() {
        return unicode ? "╔" : "+";
    }

    public String doubleTopRight() {
        return unicode ? "╗" : "+";
    }

    public String doubleBottomLeft() {
        return unicode ? "╚" : "+";
    }

    public String doubleBottomRight() {
        return unicode ? "╝" : "+";
    }

    public String star() {
        return unicode ? "★" : "*";
    }

    public String known() {
        return unicode ? "◎" : "O";
    }

    public String unknown() {
        return unicode ? "·" : ".";
    }

    public String routeHorizontal() {
        return unicode ? "━" : "=";
    }

    public String routeVertical() {
        return unicode ? "│" : "|";
    }

    public String bullet() {
        return unicode ? "●" : "o";
    }

    public String warn() {
        return unicode ? "▲" : "!";
    }

    public String arrow() {
        return unicode ? "→" : ">";
    }

    public String barFull() {
        return unicode ? "█" : "#";
    }

    public String barEmpty() {
        return unicode ? "░" : "-";
    }
}
