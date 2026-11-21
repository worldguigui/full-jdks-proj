package fc.extensions;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.FlavorEvent;
import java.awt.datatransfer.FlavorListener;
import java.awt.datatransfer.Transferable;

/**
 * 进程内剪贴板：在 {@link Clipboard#setContents} 后主动通知 {@link FlavorListener}。
 *
 * <p>JDK 的 {@code Clipboard} 基类在无窗口系统时不会触发 {@link FlavorEvent}，
 * 只有系统剪贴板实现会监听平台事件。这里保留基类的持有者/监听器语义，
 * 在内容变化时同步派发事件；单个监听器抛出的运行时异常不会打断复制。</p>
 */
public final class LocalClipboard extends Clipboard {

    public LocalClipboard(String name) {
        super(name);
    }

    @Override
    public synchronized void setContents(Transferable contents, ClipboardOwner owner) {
        // 基类只替换引用，不通知旧持有者；系统剪贴板会通知，此处补齐该语义。
        ClipboardOwner previousOwner = this.owner;
        Transferable previousContents = this.contents;
        super.setContents(contents, owner);
        if (previousOwner != null && previousOwner != owner) {
            try {
                previousOwner.lostOwnership(this, previousContents);
            } catch (RuntimeException ignored) {
                // 持有者回调故障不应让剪贴板复制失败。
            }
        }
        FlavorEvent event = new FlavorEvent(this);
        for (FlavorListener listener : getFlavorListeners()) {
            try {
                listener.flavorsChanged(event);
            } catch (RuntimeException ignored) {
                // 监听器故障不应让剪贴板复制失败。
            }
        }
    }
}
