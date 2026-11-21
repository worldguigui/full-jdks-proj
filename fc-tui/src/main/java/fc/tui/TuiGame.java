package fc.tui;

import fc.world.WorldState;

/**
 * TUI 与游戏核心之间的应用层边界。
 *
 * <p>TUI 只读取不可变快照（由 {@link SnapshotBuilder} 从 {@link WorldState} 构建），
 * 只通过 {@link #execute(String)} 提交命令；不直接改世界、不模拟键盘输入。
 * CLI、TUI 与未来的远程客户端共享同一套命令语义。</p>
 *
 * <p>市场、科研报告由宿主注入（它们依赖已装配的经济/科研服务）；默认实现
 * 返回占位文本，使 TUI 可以脱离完整运行时做渲染测试。</p>
 */
public interface TuiGame {

    WorldState state();

    /** 执行一条游戏命令，返回人类可读输出；不负责终端绘制。 */
    String execute(String command);

    /**
     * 当前只读快照。本地宿主从 WorldState 构建；远端 TUI 直接返回服务器
     * 传来的快照，不要求持有世界对象。
     */
    default Snapshot snapshot() {
        return SnapshotBuilder.build(this);
    }

    /**
     * 快照构建期间使用的锁。宿主（如双窗口模式）用它把命令执行与界面读取
     * 串行化；默认返回自身，测试实现无需关心并发。
     */
    default Object lock() {
        return this;
    }

    default String marketReport() {
        return "市场服务未接入";
    }

    default String researchReport() {
        return "科研服务未接入";
    }

    default String technologiesReport() {
        return "科技数据未接入";
    }
}
