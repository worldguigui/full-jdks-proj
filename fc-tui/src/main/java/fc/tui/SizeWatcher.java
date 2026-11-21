package fc.tui;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 后台终端尺寸监视器：按固定间隔探测平台尺寸，变化时回调。
 *
 * <p>Java SE 21 没有终端尺寸变化事件；Windows PowerShell / Unix stty 探测都是
 * 子进程，不能放在渲染或输入线程里同步执行。本类用守护线程周期探测，调用方
 * 只需在回调里标记重绘。停止后不再启动新探测。</p>
 */
final class SizeWatcher implements AutoCloseable {

    static final long DEFAULT_INTERVAL_MILLIS = 2_000L;

    private final Supplier<TerminalSize> current;
    private final Supplier<TerminalSize> probe;
    private final Consumer<TerminalSize> onChange;
    private final long intervalMillis;
    private volatile boolean running;
    private Thread thread;

    SizeWatcher(Supplier<TerminalSize> current, Supplier<TerminalSize> probe,
                Consumer<TerminalSize> onChange, long intervalMillis) {
        if (current == null || probe == null || onChange == null) {
            throw new IllegalArgumentException("尺寸监视依赖不能为空");
        }
        this.current = current;
        this.probe = probe;
        this.onChange = onChange;
        this.intervalMillis = Math.max(10L, intervalMillis);
    }

    synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "fc-tui-size");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        running = false;
        Thread active = thread;
        thread = null;
        if (active != null) {
            active.interrupt();
        }
    }

    private void loop() {
        while (running) {
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running) {
                return;
            }
            TerminalSize next;
            try {
                next = probe.get();
            } catch (RuntimeException e) {
                continue;
            }
            if (next == null) {
                continue;
            }
            TerminalSize previous;
            try {
                previous = current.get();
            } catch (RuntimeException e) {
                continue;
            }
            if (!running || next.equals(previous)) {
                continue;
            }
            try {
                onChange.accept(next);
            } catch (RuntimeException e) {
                // 渲染回调失败不应终止尺寸监视；下一轮继续尝试。
            }
        }
    }

    @Override
    public void close() {
        stop();
    }
}
