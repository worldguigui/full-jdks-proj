package fc.tui;

/**
 * 终端能力适配层。TUI 只通过本接口写屏，不直接依赖 {@code System.out}；
 * 测试可以注入内存终端，未来的 Raw Mode/第三方适配器也实现同一接口。
 */
public interface Terminal extends AutoCloseable {

    TerminalSize size();

    /** 当前终端是否适合 Unicode 线框与符号。 */
    boolean unicode();

    /** 是否启用真彩。 */
    boolean color();

    /** 进入全屏备用缓冲区并清屏。 */
    void enter();

    /** 恢复终端（显示光标、退出备用缓冲区）。 */
    void exit();

    void write(String text);

    void flush();

    /** 把光标放到 1 基坐标。 */
    default void moveCursor(int row, int column) {
        write(Ansi.cursor(row, column));
        flush();
    }

    @Override
    default void close() {
        exit();
    }
}
