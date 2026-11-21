package fc.extensions;

import fc.world.WorldState;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;

/**
 * 远征货单：当前局状态的只读快照，作为 {@link Transferable} 在进程内剪贴板中传递。
 *
 * <p>自定义 MIME 类型 {@code application/x-fc-manifest}，表示类为 {@code String}；
 * 同时支持标准 {@link DataFlavor#stringFlavor}，因此任何只认文本的接收方也能粘贴。
 * 货单只读、确定性：相同世界状态生成相同文本。</p>
 */
public final class CargoManifest implements Transferable {

    /** 货单的 MIME 类型（不含 class 参数）。 */
    public static final String MIME = "application/x-fc-manifest";

    /** 货单专用 flavor：MIME + {@code class=java.lang.String}。 */
    public static final DataFlavor FLAVOR;

    static {
        try {
            FLAVOR = new DataFlavor(MIME + ";class=java.lang.String", "边境货单");
        } catch (RuntimeException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final long day;
    private final String date;
    private final String credits;
    private final int ships;
    private final int locations;
    private final String ending;
    private final String text;

    /**
     * 构造货单；参数是只读快照，供世界状态抓取与外部生产者（如 Mod）共用。
     */
    public CargoManifest(long day, String date, String credits, int ships, int locations,
                         String ending) {
        if (day < 0 || ships < 0 || locations < 0) {
            throw new IllegalArgumentException("日、舰船数与地点数不能为负");
        }
        if (credits == null || date == null) {
            throw new IllegalArgumentException("日期与信用点不能为空");
        }
        this.day = day;
        this.date = date;
        this.credits = credits;
        this.ships = ships;
        this.locations = locations;
        this.ending = ending;
        this.text = "日 " + day + "（" + date + "）\n"
                + "信用点 " + credits + "\n"
                + "舰船 " + ships + " 艘\n"
                + "地点 " + locations + " 处\n"
                + "结局 " + (ending == null || ending.isBlank() ? "未定" : ending);
    }

    /** 从世界状态抓取货单。 */
    public static CargoManifest capture(WorldState state) {
        if (state == null) {
            throw new IllegalArgumentException("世界状态不能为空");
        }
        return new CargoManifest(state.now().day(), state.now().toString(),
                state.credits().stripTrailingZeros().toPlainString(),
                state.graph().ships().size(), state.graph().placeCount(),
                state.ending());
    }

    public long day() {
        return day;
    }

    public String credits() {
        return credits;
    }

    public int ships() {
        return ships;
    }

    public int locations() {
        return locations;
    }

    /** 货单正文；两种 flavor 都返回这段文本。 */
    public String text() {
        return text;
    }

    /** 一行摘要，供 CLI 与日志使用。 */
    public String summary() {
        return "日 " + day + "｜信用点 " + credits + "｜舰船 " + ships
                + " 艘｜地点 " + locations + " 处"
                + (ending == null || ending.isBlank() ? "" : "｜结局 " + ending);
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return new DataFlavor[] {FLAVOR, DataFlavor.stringFlavor};
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return flavor != null && (FLAVOR.equals(flavor)
                || DataFlavor.stringFlavor.equals(flavor));
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (!isDataFlavorSupported(flavor)) {
            throw new UnsupportedFlavorException(flavor);
        }
        return text;
    }

    @Override
    public String toString() {
        return "CargoManifest[" + summary() + "]";
    }
}
