package fc.runtime;

import fc.tui.Snapshot;
import fc.tui.TuiGame;
import fc.world.WorldState;

import java.io.IOException;

/**
 * 子窗口中的 TUI 应用层：命令与快照都经回环连接交给主进程的权威宿主。
 * 子窗口不需要持有 WorldState；页面只渲染服务器返回的不可变 Snapshot。
 */
final class RemoteTuiGame implements TuiGame {

    private final RemoteConsole remote;

    RemoteTuiGame(RemoteConsole remote) {
        this.remote = remote;
    }

    @Override
    public WorldState state() {
        throw new UnsupportedOperationException("远端 TUI 不持有 WorldState");
    }

    @Override
    public String execute(String command) {
        try {
            return remote.command(command);
        } catch (IOException e) {
            throw new IllegalStateException("远端命令失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Snapshot snapshot() {
        try {
            return remote.snapshot();
        } catch (IOException e) {
            throw new IllegalStateException("获取远端快照失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Object lock() {
        return this;
    }
}
