package fc.extensions;

import fc.testkit.FcTest;
import fc.testkit.TestRunner;
import fc.world.Ids;
import fc.world.Link;
import fc.world.Location;
import fc.world.LocationGraph;
import fc.world.Stock;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 星图工坊：多格式输出、元数据、滤镜、缩略图与确定性。 */
public final class MapAtlasTest {

    private static final Ids.LocationId L1 = new Ids.LocationId(1);
    private static final Ids.LocationId L2 = new Ids.LocationId(2);
    private static final Ids.LocationId L3 = new Ids.LocationId(3);
    private static final Ids.LocationId L4 = new Ids.LocationId(4);
    private static final Ids.LocationId L5 = new Ids.LocationId(5);
    private static final Ids.LocationId L6 = new Ids.LocationId(6);
    private static final Ids.LocationId SHIP_NODE = new Ids.LocationId(101);

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(MapAtlasTest.class))) {
            System.exit(1);
        }
    }

    private static LocationGraph graph() {
        Map<Ids.LocationId, Location> nodes = new LinkedHashMap<>();
        nodes.put(L1, new Location.StarSystem(L1, Stock.empty(), "晨曦"));
        nodes.put(L2, new Location.Planet(L2, Stock.empty(), "新伊甸"));
        nodes.put(L3, new Location.Colony(L3, Stock.empty(), "前哨-7"));
        nodes.put(L4, new Location.OrbitalStation(L4, Stock.empty(), "织女站"));
        nodes.put(L5, new Location.Anomaly(L5, Stock.empty(), "静默环"));
        nodes.put(L6, new Location.Facility(L6, Stock.empty(), "冶炼厂"));
        nodes.put(SHIP_NODE, new Location.Ship(SHIP_NODE, "远行者",
                new Ids.ShipId(1), L1, Stock.empty(), null));
        return new LocationGraph(nodes, List.of(
                new Link.WarpRoute(L1, L2, 3, 0.1, new BigDecimal("5")),
                new Link.WarpRoute(L2, L3, 4, 0.2, new BigDecimal("8")),
                new Link.SurfaceRoute(L3, L4, 2, 0.05, new BigDecimal("1")),
                new Link.WarpRoute(L5, L6, 6, 0.3, new BigDecimal("12"))));
    }

    private static MapAtlas.AtlasOptions options() {
        return new MapAtlas.AtlasOptions("测试星图", 480, 320, true, true, 0.85f);
    }

    private static Path tempDir() throws Exception {
        return Files.createTempDirectory("fc-atlas-test-");
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0,
                image.getWidth());
    }

    @FcTest
    public void rendersPngWithTextMetadata() throws Exception {
        Path dir = tempDir();
        try {
            Path png = MapAtlas.render(graph(), dir.resolve("atlas.png"), "png", options());
            assert Files.size(png) > 5000 : Files.size(png);
            BufferedImage image = ImageIO.read(png.toFile());
            assert image != null;
            assert image.getWidth() == 480 && image.getHeight() == 320;
            Map<String, String> meta = MapAtlas.readTextMetadata(png);
            assert "Frontier Chronicle MapAtlas".equals(meta.get("Generator")) : meta;
            assert "6".equals(meta.get("Locations")) : meta;
            assert "4".equals(meta.get("Links")) : meta;
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void rendersJpegWithCommentMetadataAndQuality() throws Exception {
        Path dir = tempDir();
        try {
            MapAtlas.AtlasOptions options = new MapAtlas.AtlasOptions(
                    "JPEG 测试", 480, 320, false, false, 0.6f);
            Path jpg = MapAtlas.render(graph(), dir.resolve("atlas.jpg"), "jpg", options);
            BufferedImage image = ImageIO.read(jpg.toFile());
            assert image != null;
            assert image.getWidth() == 480 && image.getHeight() == 320;
            assert image.getType() != BufferedImage.TYPE_INT_ARGB;
            Map<String, String> meta = MapAtlas.readTextMetadata(jpg);
            assert "Frontier Chronicle MapAtlas".equals(meta.get("Generator")) : meta;
            assert meta.containsKey("Title") : meta;
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void sameWorldAndOptionsProduceSamePixels() {
        BufferedImage a = MapAtlas.draw(graph(), options());
        BufferedImage b = MapAtlas.draw(graph(), options());
        assert Arrays.equals(pixels(a), pixels(b)) : "同一星图应逐像素一致";
    }

    @FcTest
    public void filtersAndThumbnailOperate() {
        BufferedImage source = MapAtlas.draw(graph(), options());
        int[] original = pixels(source);
        assert !Arrays.equals(original, pixels(MapAtlas.sharpen(source))) : "锐化应改变像素";
        assert !Arrays.equals(original, pixels(MapAtlas.blur(source))) : "模糊应改变像素";
        assert !Arrays.equals(original, pixels(MapAtlas.brighten(source, 1.3f, 20f)))
                : "亮度调整应改变像素";
        BufferedImage thumb = MapAtlas.thumbnail(source, 100);
        assert Math.max(thumb.getWidth(), thumb.getHeight()) == 100
                : thumb.getWidth() + "x" + thumb.getHeight();
        assert thumb.getWidth() < source.getWidth();
    }

    @FcTest
    public void regionReadAndSrgbConversion() throws Exception {
        Path dir = tempDir();
        try {
            Path png = MapAtlas.render(graph(), dir.resolve("atlas.png"), "png", options());
            BufferedImage region = MapAtlas.readRegion(png, new Rectangle(10, 20, 30, 40));
            assert region.getWidth() == 30 && region.getHeight() == 40
                    : region.getWidth() + "x" + region.getHeight();
            BufferedImage argb = MapAtlas.draw(graph(), options());
            BufferedImage rgb = MapAtlas.toSrgb(argb);
            assert rgb.getType() == BufferedImage.TYPE_INT_RGB : rgb.getType();
            assert rgb.getWidth() == argb.getWidth() && rgb.getHeight() == argb.getHeight();
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void rejectsSmallSizesAndUnknownFormats() throws Exception {
        boolean badSize = false;
        try {
            new MapAtlas.AtlasOptions("x", 200, 200, true, true, 0.9f);
        } catch (IllegalArgumentException e) {
            badSize = true;
        }
        assert badSize : "小于 320×240 应被拒绝";
        boolean badQuality = false;
        try {
            new MapAtlas.AtlasOptions("x", 480, 320, true, true, 2f);
        } catch (IllegalArgumentException e) {
            badQuality = true;
        }
        assert badQuality : "JPEG 质量越界应被拒绝";
        boolean badFormat = false;
        try {
            MapAtlas.render(graph(), Path.of("atlas.bmp"), "bmp", options());
        } catch (IllegalArgumentException e) {
            badFormat = true;
        }
        assert badFormat : "未知格式应被拒绝";
        boolean nullGraph = false;
        try {
            MapAtlas.draw(null, options());
        } catch (IllegalArgumentException e) {
            nullGraph = true;
        }
        assert nullGraph;
    }
}
