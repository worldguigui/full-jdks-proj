package fc.extensions;

import fc.testkit.FcTest;
import fc.testkit.TestRunner;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.FlavorMap;
import java.awt.datatransfer.FlavorTable;
import java.awt.datatransfer.MimeTypeParseException;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.SystemFlavorMap;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 货单 Transferable、进程内剪贴板、FlavorMap 映射与 MIME 校验。 */
public final class ManifestTransferTest {

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(ManifestTransferTest.class))) {
            System.exit(1);
        }
    }

    private static CargoManifest manifest() {
        return new CargoManifest(3, "2200-01-04", "1234", 4, 16, "星门启航");
    }

    @FcTest
    public void cargoManifestExposesCustomAndTextFlavors() throws Exception {
        CargoManifest manifest = manifest();
        assert manifest.summary().contains("信用点 1234") : manifest.summary();
        assert manifest.text().contains("日 3") : manifest.text();
        assert manifest.text().contains("星门启航") : manifest.text();

        DataFlavor[] flavors = manifest.getTransferDataFlavors();
        assert flavors.length == 2 : flavors.length;
        assert CargoManifest.FLAVOR.getMimeType().contains("application/x-fc-manifest")
                : CargoManifest.FLAVOR.getMimeType();
        assert manifest.isDataFlavorSupported(CargoManifest.FLAVOR);
        assert manifest.isDataFlavorSupported(DataFlavor.stringFlavor);
        assert !manifest.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        assert manifest.getTransferData(DataFlavor.stringFlavor).equals(manifest.text());
        assert manifest.getTransferData(CargoManifest.FLAVOR).equals(manifest.text());

        boolean rejected = false;
        try {
            manifest.getTransferData(DataFlavor.javaFileListFlavor);
        } catch (UnsupportedFlavorException e) {
            rejected = true;
        }
        assert rejected : "不支持的 flavor 必须抛 UnsupportedFlavorException";

        boolean negative = false;
        try {
            new CargoManifest(-1, "x", "0", 0, 0, "");
        } catch (IllegalArgumentException e) {
            negative = true;
        }
        assert negative;
    }

    @FcTest
    public void clipboardRoundTripReportsOwnershipAndFlavorEvents() throws Exception {
        Clipboard clipboard = new LocalClipboard("测试货单");
        ManifestTransfer.FlavorCounter counter = new ManifestTransfer.FlavorCounter();
        clipboard.addFlavorListener(counter);
        try {
            AtomicBoolean lost = new AtomicBoolean();
            ClipboardOwner owner = (source, contents) -> lost.set(true);
            CargoManifest manifest = manifest();
            clipboard.setContents(manifest, owner);

            assert clipboard.isDataFlavorAvailable(CargoManifest.FLAVOR);
            assert clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor);
            assert clipboard.getData(DataFlavor.stringFlavor).equals(manifest.text());
            assert counter.count() >= 1 : "setContents 应触发 FlavorEvent";
            assert clipboard.getFlavorListeners().length == 1;
            assert ManifestTransfer.describe(clipboard).contains("边境货单")
                    : ManifestTransfer.describe(clipboard);

            clipboard.setContents(new StringSelection("边境电报"),
                    (source, contents) -> {
                    });
            assert lost.get() : "替换内容时必须通知原 ClipboardOwner 失去所有权";
            assert ManifestTransfer.readText(clipboard.getContents(null)).equals("边境电报");
            assert clipboard.getData(DataFlavor.stringFlavor).equals("边境电报");
        } finally {
            clipboard.removeFlavorListener(counter);
        }
        assert clipboard.getFlavorListeners().length == 0;
    }

    @FcTest
    public void flavorMapRegistersNativeNameAndMimeValidationRejectsBadInput() throws Exception {
        ManifestTransfer.ensureRegistered();
        FlavorMap map = SystemFlavorMap.getDefaultFlavorMap();
        assert map instanceof FlavorTable : map.getClass();
        FlavorTable table = (FlavorTable) map;
        assert table.getNativesForFlavor(CargoManifest.FLAVOR)
                .contains(ManifestTransfer.NATIVE_NAME) : "应登记原生名";
        assert table.getFlavorsForNative(ManifestTransfer.NATIVE_NAME)
                .contains(CargoManifest.FLAVOR) : "原生名应反向映射回 flavor";

        Map<DataFlavor, String> natives = map.getNativesForFlavors(
                new DataFlavor[] {CargoManifest.FLAVOR});
        assert natives.values().contains(ManifestTransfer.NATIVE_NAME) : natives;

        DataFlavor custom = ManifestTransfer.validateFlavor("text/x-frontier-manifest");
        assert custom.getMimeType().startsWith("text/x-frontier-manifest")
                : custom.getMimeType();

        boolean noSlash = false;
        try {
            ManifestTransfer.validateFlavor("not-a-mime");
        } catch (MimeTypeParseException e) {
            noSlash = e.getMessage().contains("type/subtype");
        }
        assert noSlash : "缺少 type/subtype 必须报 MimeTypeParseException";

        boolean emptyType = false;
        try {
            ManifestTransfer.validateFlavor("/missing");
        } catch (MimeTypeParseException e) {
            emptyType = true;
        }
        assert emptyType;
    }

    @FcTest
    public void emptyClipboardAndUnsupportedTransferableAreReported() throws Exception {
        Clipboard empty = new Clipboard("空货板");
        assert empty.getContents(null) == null;
        assert ManifestTransfer.describe(empty).contains("为空")
                : ManifestTransfer.describe(empty);

        boolean nullRejected = false;
        try {
            ManifestTransfer.readText(null);
        } catch (UnsupportedFlavorException e) {
            nullRejected = true;
        }
        assert nullRejected;

        Transferable filesOnly = new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[] {DataFlavor.javaFileListFlavor};
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor flavor) {
                return DataFlavor.javaFileListFlavor.equals(flavor);
            }

            @Override
            public Object getTransferData(DataFlavor flavor) {
                throw new UnsupportedOperationException("测试桩不提供数据");
            }
        };
        boolean unsupported = false;
        try {
            ManifestTransfer.readText(filesOnly);
        } catch (UnsupportedFlavorException e) {
            unsupported = true;
        }
        assert unsupported : "只有文件列表 flavor 时必须报告不支持文本";

        boolean badState = false;
        try {
            ManifestTransfer.capture(null);
        } catch (IllegalArgumentException e) {
            badState = true;
        }
        assert badState;
    }
}
