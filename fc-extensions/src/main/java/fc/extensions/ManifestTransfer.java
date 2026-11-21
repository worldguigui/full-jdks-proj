package fc.extensions;

import fc.world.WorldState;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.FlavorEvent;
import java.awt.datatransfer.FlavorListener;
import java.awt.datatransfer.FlavorMap;
import java.awt.datatransfer.FlavorTable;
import java.awt.datatransfer.MimeTypeParseException;
import java.awt.datatransfer.SystemFlavorMap;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 进程内剪贴板的货单工具：生成货单、读取文本、展示 flavor 与原生类型映射。
 *
 * <p>系统剪贴板需要图形环境，本项目的 CLI 在服务器上也应可用，因此使用
 * {@link Clipboard} 的进程内实例，而不是 {@code Toolkit.getSystemClipboard()}。
 * {@link SystemFlavorMap} 把自定义 flavor 登记为原生名 {@code FC_MANIFEST}，
 * 通过 {@link FlavorTable} 双向查询；不依赖任何本地窗口系统。</p>
 */
public final class ManifestTransfer {

    /** 自定义 flavor 的原生名。 */
    public static final String NATIVE_NAME = "FC_MANIFEST";

    private static volatile boolean registered;

    private ManifestTransfer() {
    }

    /**
     * 把货单 flavor 登记到 {@link SystemFlavorMap}（幂等）。
     *
     * <p>显式初始化而不是依赖静态块：{@link #NATIVE_NAME} 是编译期常量，
     * 仅读取该常量不会触发类初始化；调用方（含测试）应先调用本方法。</p>
     */
    public static void ensureRegistered() {
        if (registered) {
            return;
        }
        synchronized (ManifestTransfer.class) {
            if (registered) {
                return;
            }
            FlavorMap map = SystemFlavorMap.getDefaultFlavorMap();
            if (map instanceof SystemFlavorMap system) {
                system.addUnencodedNativeForFlavor(CargoManifest.FLAVOR, NATIVE_NAME);
                system.addFlavorForUnencodedNative(NATIVE_NAME, CargoManifest.FLAVOR);
            }
            registered = true;
        }
    }

    /** 从世界状态生成货单。 */
    public static CargoManifest capture(WorldState state) {
        ensureRegistered();
        return CargoManifest.capture(state);
    }

    /**
     * 从任意 {@link Transferable} 读取文本：优先 {@link DataFlavor#stringFlavor}，
     * 其次货单 flavor；都不支持时抛 {@link UnsupportedFlavorException}。
     */
    public static String readText(Transferable contents)
            throws UnsupportedFlavorException, IOException {
        if (contents == null) {
            throw new UnsupportedFlavorException(DataFlavor.stringFlavor);
        }
        if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            return (String) contents.getTransferData(DataFlavor.stringFlavor);
        }
        if (contents.isDataFlavorSupported(CargoManifest.FLAVOR)) {
            return (String) contents.getTransferData(CargoManifest.FLAVOR);
        }
        throw new UnsupportedFlavorException(DataFlavor.stringFlavor);
    }

    /** 展示剪贴板内容、flavor 与原生映射；空剪贴板给出提示。 */
    public static String describe(Clipboard clipboard) {
        if (clipboard == null) {
            throw new IllegalArgumentException("剪贴板不能为空");
        }
        ensureRegistered();
        Transferable contents = clipboard.getContents(null);
        if (contents == null) {
            return "剪贴板「" + clipboard.getName() + "」为空（manifest copy 放入货单）";
        }
        FlavorMap map = SystemFlavorMap.getDefaultFlavorMap();
        FlavorTable table = map instanceof FlavorTable candidate ? candidate : null;
        StringBuilder sb = new StringBuilder("剪贴板「" + clipboard.getName() + "」");
        for (DataFlavor flavor : contents.getTransferDataFlavors()) {
            sb.append("\n  ").append(flavor.getHumanPresentableName())
                    .append(" [").append(flavor.getMimeType()).append(']');
            List<String> natives = table == null
                    ? List.of() : table.getNativesForFlavor(flavor);
            if (!natives.isEmpty()) {
                sb.append(" ↔ ").append(String.join(", ", natives));
            }
        }
        List<String> mapping = table == null ? List.of() : table.getNativesForFlavor(
                CargoManifest.FLAVOR);
        sb.append("\n  原生名 ").append(NATIVE_NAME).append(" → 映射 ")
                .append(mapping.isEmpty() ? "未登记" : String.join(", ", mapping));
        return sb.toString();
    }

    /**
     * 校验用户给出的 MIME 文本并构造 flavor。
     *
     * <p>{@link MimeTypeParseException} 是 JDK 为 MIME 解析定义的受检异常；
     * 这里把「缺少 type/subtype」和底层 {@link DataFlavor} 的参数错误统一成它，
     * 供调用方区分输入错误与运行时故障。</p>
     */
    public static DataFlavor validateFlavor(String mime) throws MimeTypeParseException {
        ensureRegistered();
        if (mime == null || mime.isBlank() || !mime.contains("/")
                || mime.startsWith("/") || mime.endsWith("/")) {
            throw new MimeTypeParseException("MIME 必须形如 type/subtype：" + mime);
        }
        try {
            return new DataFlavor(mime, "校验用 flavor");
        } catch (IllegalArgumentException e) {
            throw new MimeTypeParseException("MIME 解析失败：" + mime);
        }
    }

    /** 统计剪贴板 flavor 变更次数，供 CLI 状态展示与测试订阅。 */
    public static final class FlavorCounter implements FlavorListener {
        private final AtomicInteger events = new AtomicInteger();

        @Override
        public void flavorsChanged(FlavorEvent event) {
            events.incrementAndGet();
        }

        public int count() {
            return events.get();
        }
    }
}
