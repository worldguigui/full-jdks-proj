package fc.extensions;

import fc.world.Ids;
import fc.world.Link;
import fc.world.Location;
import fc.world.LocationGraph;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.QuadCurve2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.awt.image.RescaleOp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.w3c.dom.NodeList;

/**
 * 星图工坊：把世界图渲染成带图例、比例尺、罗盘、缩略图的出版级星图。
 *
 * <p>与 {@link StarMapRenderer} 的极简 PNG 不同，本类是完整的制图管线：
 * 渐变星野、模糊星云（ConvolveOp）、曲线航线（QuadCurve2D/CubicCurve2D）、
 * 多种节点形状（Ellipse2D/RoundRectangle2D/Rectangle2D/Arc2D/GeneralPath）、
 * 字形轮廓标题（GlyphVector）、带字距的副标题（TextAttribute）、自动换行说明
 * （LineBreakMeasurer/TextLayout）、缩放内嵌图（AffineTransformOp）、
 * 索引用 Area 合成，以及 PNG tEXt / JPEG COM 元数据写入与读回。</p>
 *
 * <p>几何布局只依赖 id 排序与世界图，不依赖系统时钟或随机数，
 * 因此同一世界、同一选项会得到逐像素一致的图像（字体栅格化由平台提供，
 * 布局与线宽仍然确定）。</p>
 */
public final class MapAtlas {

    /** 制图选项；构造时校验，避免调用方传入无意义尺寸。 */
    public record AtlasOptions(String title, int width, int height,
                               boolean grid, boolean nebula, float jpegQuality) {
        public AtlasOptions {
            if (title == null || title.isBlank()) {
                title = "Frontier Chronicle — 星图总览";
            }
            if (width < 320 || height < 240) {
                throw new IllegalArgumentException("星图尺寸至少 320×240");
            }
            if (width > 8192 || height > 8192) {
                throw new IllegalArgumentException("星图尺寸不得超过 8192×8192");
            }
            if (jpegQuality < 0.05f || jpegQuality > 1.0f) {
                throw new IllegalArgumentException("JPEG 质量须在 0.05–1.0 之间");
            }
        }

        public static AtlasOptions defaults() {
            return new AtlasOptions("Frontier Chronicle — 星图总览", 1280, 800, true, true, 0.9f);
        }
    }

    private MapAtlas() {
    }

    /** 渲染并写出图片；format 接受 png/jpeg/jpg。 */
    public static Path render(LocationGraph graph, Path output, String format,
                              AtlasOptions options) throws IOException {
        if (graph == null) {
            throw new IllegalArgumentException("graph 不能为空");
        }
        if (output == null) {
            throw new IllegalArgumentException("output 不能为空");
        }
        String normalizedFormat = normalizeFormat(format);
        BufferedImage image = draw(graph, options);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("Title", options.title().replaceAll("[^\\x20-\\x7E]", "?"));
        metadata.put("Generator", "Frontier Chronicle MapAtlas");
        metadata.put("Locations", Integer.toString(placeCount(graph)));
        metadata.put("Links", Integer.toString(graph.links().size()));
        write(image, output, normalizedFormat, options.jpegQuality(), metadata);
        return output.toAbsolutePath().normalize();
    }

    /** 只绘制到内存，不接触文件系统；测试与预览复用同一入口。 */
    public static BufferedImage draw(LocationGraph graph, AtlasOptions options) {
        if (graph == null) {
            throw new IllegalArgumentException("graph 不能为空");
        }
        AtlasOptions o = options == null ? AtlasOptions.defaults() : options;
        int w = o.width();
        int h = o.height();
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            prepare(g, w, h);
            drawStarfield(g, w, h);
            if (o.nebula()) {
                drawNebula(g, w, h);
            }
            if (o.grid()) {
                drawGrid(g, w, h);
            }

            List<Location> places = places(graph);
            Map<Ids.LocationId, Point2D.Double> positions =
                    layout(places, w, h);
            drawFrame(g, w, h);
            drawRoutes(g, graph, positions);
            drawNodes(g, places, positions);
            drawShips(g, graph, positions);
            drawCompass(g, w, h);
            drawTitle(g, o, w);
            drawLegend(g, graph, places, w, h);
            drawScaleBar(g, w, h);
            drawInset(g, image, w, h);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** 写出图片并附加文本元数据（PNG 用 tEXt，JPEG 用 COM）。 */
    public static void write(BufferedImage image, Path output, String format,
                             float quality, Map<String, String> metadata) throws IOException {
        String fmt = normalizeFormat(format);
        Path normalized = output.toAbsolutePath().normalize();
        if (normalized.getParent() != null) {
            Files.createDirectories(normalized.getParent());
        }
        var writers = ImageIO.getImageWritersByFormatName(fmt);
        if (!writers.hasNext()) {
            throw new IOException("没有可用的 " + fmt + " 编码器");
        }
        ImageWriter writer = writers.next();
        BufferedImage source = fmt.equals("jpeg") ? toSrgb(image) : image;
        ImageWriteParam param = writer.getDefaultWriteParam();
        ImageTypeSpecifier type = ImageTypeSpecifier.createFromRenderedImage(source);
        IIOMetadata imageMetadata = writer.getDefaultImageMetadata(type, param);
        applyTextMetadata(imageMetadata, metadata, fmt);
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(Math.max(0.05f, Math.min(1.0f, quality)));
        }
        try (ImageOutputStream out = ImageIO.createImageOutputStream(normalized.toFile())) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(source, null, imageMetadata), param);
            out.flush();
        } finally {
            writer.dispose();
        }
    }

    /** 读取 PNG tEXt / JPEG COM 文本元数据。 */
    public static Map<String, String> readTextMetadata(Path image) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ImageInputStream in = ImageIO.createImageInputStream(image.toFile())) {
            if (in == null) {
                throw new IOException("无法打开图片: " + image);
            }
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("无法识别图片格式: " + image);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, false, false);
                IIOMetadata metadata = reader.getImageMetadata(0);
                String nativeFormat = metadata.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(nativeFormat);
                NodeList textEntries = root.getElementsByTagName("tEXtEntry");
                for (int i = 0; i < textEntries.getLength(); i++) {
                    IIOMetadataNode entry = (IIOMetadataNode) textEntries.item(i);
                    out.put(entry.getAttribute("keyword"), entry.getAttribute("value"));
                }
                NodeList comments = root.getElementsByTagName("com");
                for (int i = 0; i < comments.getLength(); i++) {
                    IIOMetadataNode comment = (IIOMetadataNode) comments.item(i);
                    for (String line : comment.getAttribute("comment").split("\\n")) {
                        int eq = line.indexOf('=');
                        if (eq > 0) {
                            out.put(line.substring(0, eq), line.substring(eq + 1));
                        }
                    }
                }
            } finally {
                reader.dispose();
            }
        }
        return out;
    }

    /** 只读取图片的一块区域（ImageReadParam.setSourceRegion）。 */
    public static BufferedImage readRegion(Path image, Rectangle region) throws IOException {
        if (region == null || region.width <= 0 || region.height <= 0) {
            throw new IllegalArgumentException("区域必须为正");
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(image.toFile())) {
            if (in == null) {
                throw new IOException("无法打开图片: " + image);
            }
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("无法识别图片格式: " + image);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceRegion(region);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    /** 3×3 锐化（ConvolveOp/Kernel）。 */
    public static BufferedImage sharpen(BufferedImage source) {
        Kernel kernel = new Kernel(3, 3, new float[] {
                0f, -1f, 0f,
                -1f, 5f, -1f,
                0f, -1f, 0f});
        return new ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null)
                .filter(source, null);
    }

    /** 3×3 均值模糊（ConvolveOp/Kernel），用于星云柔化。 */
    public static BufferedImage blur(BufferedImage source) {
        float[] data = new float[9];
        java.util.Arrays.fill(data, 1f / 9f);
        Kernel kernel = new Kernel(3, 3, data);
        return new ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null)
                .filter(source, null);
    }

    /** 亮度偏移（RescaleOp）。 */
    public static BufferedImage brighten(BufferedImage source, float scale, float offset) {
        return new RescaleOp(scale, offset, null).filter(source, null);
    }

    /** 高质量缩略图（AffineTransformOp 双线性）。 */
    public static BufferedImage thumbnail(BufferedImage source, int maxSide) {
        if (maxSide <= 0) {
            throw new IllegalArgumentException("缩略图边长必须为正");
        }
        double scale = Math.min(1.0,
                Math.min(maxSide / (double) source.getWidth(),
                        maxSide / (double) source.getHeight()));
        if (scale >= 1.0) {
            return source;
        }
        AffineTransformOp op = new AffineTransformOp(
                AffineTransform.getScaleInstance(scale, scale),
                AffineTransformOp.TYPE_BILINEAR);
        BufferedImage out = new BufferedImage(
                Math.max(1, (int) Math.round(source.getWidth() * scale)),
                Math.max(1, (int) Math.round(source.getHeight() * scale)),
                BufferedImage.TYPE_INT_ARGB);
        return op.filter(source, out);
    }

    /** 转换为 sRGB 的 RGB 图像（ColorConvertOp），供 JPEG 等不支持 alpha 的格式使用。 */
    public static BufferedImage toSrgb(BufferedImage source) {
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setColor(new Color(0x05070F));
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        ColorConvertOp op = new ColorConvertOp(
                ColorSpace.getInstance(ColorSpace.CS_sRGB), null);
        BufferedImage converted = op.filter(rgb, null);
        if (converted.getType() == BufferedImage.TYPE_INT_RGB) {
            return converted;
        }
        BufferedImage normalized = new BufferedImage(converted.getWidth(),
                converted.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D out = normalized.createGraphics();
        try {
            out.drawImage(converted, 0, 0, null);
        } finally {
            out.dispose();
        }
        return normalized;
    }

    // ------------------------------------------------------------ 绘制

    private static void prepare(Graphics2D g, int w, int h) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE);
        g.setPaint(new GradientPaint(0, 0, new Color(0x05070F),
                w, h, new Color(0x15233F)));
        g.fillRect(0, 0, w, h);
    }

    private static void drawStarfield(Graphics2D g, int w, int h) {
        java.util.Random random = new java.util.Random(0x5EED_1234L);
        int count = Math.max(120, w * h / 5200);
        for (int i = 0; i < count; i++) {
            double x = random.nextDouble() * w;
            double y = random.nextDouble() * h;
            int alpha = 40 + random.nextInt(150);
            float r = 0.6f + random.nextFloat() * 1.3f;
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                    alpha / 255f));
            g.setColor(random.nextInt(5) == 0 ? new Color(0xFFE3A3) : new Color(0xE8F0FF));
            g.fill(new Ellipse2D.Double(x, y, r, r));
        }
        g.setComposite(AlphaComposite.SrcOver);
    }

    private static void drawNebula(Graphics2D g, int w, int h) {
        BufferedImage layer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D n = layer.createGraphics();
        try {
            n.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            n.setComposite(AlphaComposite.SrcOver.derive(0.16f));
            n.setPaint(new GradientPaint(w * 0.18f, h * 0.22f,
                    new Color(0x3E6DD8), w * 0.55f, h * 0.6f, new Color(0x7B3FB8)));
            n.fill(new Ellipse2D.Double(w * 0.08, h * 0.10, w * 0.52, h * 0.46));
            n.setPaint(new GradientPaint(w * 0.8f, h * 0.2f,
                    new Color(0x2E9E8F), w * 0.6f, h * 0.7f, new Color(0x1F4B8E)));
            n.fill(new QuadCurve2D.Double(w * 0.55, h * 0.15,
                    w * 0.95, h * 0.45, w * 0.7, h * 0.85));
        } finally {
            n.dispose();
        }
        BufferedImage soft = blur(brighten(layer, 1.15f, 12f));
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
        g.drawImage(soft, 0, 0, null);
        g.setComposite(AlphaComposite.SrcOver);
    }

    private static void drawGrid(Graphics2D g, int w, int h) {
        g.setColor(new Color(0x9FB4E8));
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.13f));
        g.setStroke(new BasicStroke(0.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[] {2f, 7f}, 0f));
        for (int x = 80; x < w - 80; x += 80) {
            g.draw(new Line2D.Double(x, 64, x, h - 72));
        }
        for (int y = 96; y < h - 72; y += 80) {
            g.draw(new Line2D.Double(64, y, w - 64, y));
        }
        g.setComposite(AlphaComposite.SrcOver);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 9));
        g.setColor(new Color(0x8FA6D8));
        for (int x = 80; x < w - 80; x += 160) {
            g.drawString("α" + (x / 80), x + 2, h - 78);
        }
        for (int y = 96; y < h - 72; y += 160) {
            g.drawString("δ" + (y / 80), 24, y + 3);
        }
    }

    private static void drawFrame(Graphics2D g, int w, int h) {
        Area outer = new Area(new RoundRectangle2D.Double(10, 10, w - 20, h - 20, 30, 30));
        Area inner = new Area(new RoundRectangle2D.Double(14, 14, w - 28, h - 28, 24, 24));
        outer.subtract(inner);
        g.setColor(new Color(0x7FA7E8));
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
        g.fill(outer);
        g.setComposite(AlphaComposite.SrcOver);
    }

    private static void drawRoutes(Graphics2D g, LocationGraph graph,
                                   Map<Ids.LocationId, Point2D.Double> positions) {
        int index = 0;
        for (Link link : graph.links()) {
            Point2D.Double a = positions.get(link.from());
            Point2D.Double b = positions.get(link.to());
            if (a == null || b == null) {
                index++;
                continue;
            }
            g.setPaint(new GradientPaint((float) a.x, (float) a.y, new Color(0x5E82C8),
                    (float) b.x, (float) b.y, new Color(0x9BB6EC)));
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            if (Math.abs(a.x - b.x) + Math.abs(a.y - b.y) > 380) {
                double bend = (index % 2 == 0 ? 1 : -1)
                        * Math.min(70, a.distance(b) * 0.18);
                double mx = (a.x + b.x) / 2 + bend;
                double my = (a.y + b.y) / 2 - bend;
                g.draw(new CubicCurve2D.Double(a.x, a.y, mx, my, mx, my, b.x, b.y));
            } else {
                double bend = (index % 2 == 0 ? 1 : -1) * 18;
                g.draw(new QuadCurve2D.Double(a.x, a.y,
                        (a.x + b.x) / 2, (a.y + b.y) / 2 + bend, b.x, b.y));
            }
            index++;
        }
    }

    private static void drawNodes(Graphics2D g, List<Location> places,
                                  Map<Ids.LocationId, Point2D.Double> positions) {
        Font labelFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        g.setFont(labelFont);
        FontMetrics metrics = g.getFontMetrics();
        for (Location place : places) {
            Point2D.Double p = positions.get(place.id());
            if (p == null) {
                continue;
            }
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
            g.setColor(colorOf(place));
            g.fill(new Ellipse2D.Double(p.x - 17, p.y - 17, 34, 34));
            g.setComposite(AlphaComposite.SrcOver);

            var shape = shapeOf(place, p);
            g.setColor(colorOf(place));
            g.fill(shape);
            g.setColor(new Color(0xF4F8FF));
            g.setStroke(new BasicStroke(1.1f));
            g.draw(shape);

            String label = truncate(place.name(), 18);
            float lx = (float) p.x + 14;
            float ly = (float) p.y + metrics.getAscent() / 2f - 1;
            g.setColor(new Color(0xCBD8F5));
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f));
            g.fill(new RoundRectangle2D.Double(lx - 3, ly - metrics.getAscent() - 1,
                    metrics.stringWidth(label) + 6, metrics.getHeight(), 5, 5));
            g.setComposite(AlphaComposite.SrcOver);
            g.setColor(new Color(0xEAF0FF));
            g.drawString(label, lx, ly);
        }
    }

    private static java.awt.Shape shapeOf(Location place, Point2D.Double p) {
        double x = p.x;
        double y = p.y;
        return switch (place) {
            case Location.StarSystem s -> star(x, y, 11, 5.4);
            case Location.Planet pn -> new Ellipse2D.Double(x - 8, y - 8, 16, 16);
            case Location.OrbitalStation o ->
                    new RoundRectangle2D.Double(x - 9, y - 6, 18, 12, 6, 6);
            case Location.Anomaly a ->
                    new QuadCurve2D.Double(x - 10, y + 6, x, y - 14, x + 10, y + 6);
            case Location.Colony c ->
                    new RoundRectangle2D.Double(x - 9, y - 8, 18, 16, 4, 4);
            case Location.Facility f ->
                    new Rectangle2D.Double(x - 6, y - 6, 12, 12);
            case Location.ExtensionLocation e ->
                    new Polygon(new int[] {(int) x, (int) x + 9, (int) x},
                            new int[] {(int) y - 9, (int) y + 7, (int) y + 7}, 3);
            case Location.Ship s -> new Ellipse2D.Double(x - 5, y - 5, 10, 10);
        };
    }

    private static Path2D star(double cx, double cy, double outer, double inner) {
        Path2D.Double path = new Path2D.Double();
        for (int i = 0; i < 10; i++) {
            double radius = i % 2 == 0 ? outer : inner;
            double angle = -Math.PI / 2 + i * Math.PI / 5;
            double x = cx + radius * Math.cos(angle);
            double y = cy + radius * Math.sin(angle);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.closePath();
        return path;
    }

    private static void drawShips(Graphics2D g, LocationGraph graph,
                                  Map<Ids.LocationId, Point2D.Double> positions) {
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        for (Location.Ship ship : graph.ships()) {
            Point2D.Double p = positions.get(ship.parent());
            if (p == null) {
                continue;
            }
            double offset = 18 + (ship.shipId().id() % 3) * 6;
            double x = p.x + offset;
            double y = p.y - offset;
            Path2D.Double triangle = new Path2D.Double();
            triangle.moveTo(x, y - 7);
            triangle.lineTo(x - 6, y + 6);
            triangle.lineTo(x + 6, y + 6);
            triangle.closePath();
            g.setColor(new Color(0x9AE6B4));
            g.fill(triangle);
            g.setColor(new Color(0x0B1020));
            g.setStroke(new BasicStroke(0.8f));
            g.draw(triangle);
            g.setColor(new Color(0xBFF7D4));
            g.drawString(truncate(ship.name(), 12), (float) x + 9, (float) y + 4);
        }
    }

    private static void drawCompass(Graphics2D g, int w, int h) {
        int cx = w - 84;
        int cy = h - 108;
        g.setColor(new Color(0x9FB4E8));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(new Ellipse2D.Double(cx - 28, cy - 28, 56, 56));
        g.draw(new Ellipse2D.Double(cx - 18, cy - 18, 36, 36));
        Polygon north = new Polygon(
                new int[] {cx, cx + 6, cx, cx - 6},
                new int[] {cy - 24, cy, cy + 8, cy}, 4);
        g.setColor(new Color(0xFFD166));
        g.fill(north);
        g.setColor(new Color(0xEAF0FF));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.drawString("N", cx - 4, cy - 32);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        g.drawString("E", cx + 32, cy + 4);
        g.drawString("S", cx - 4, cy + 44);
        g.drawString("W", cx - 42, cy + 4);
    }

    private static void drawTitle(Graphics2D g, AtlasOptions o, int w) {
        String title = truncate(o.title(), 46);
        Font font = new Font(Font.SERIF, Font.BOLD, 26);
        FontRenderContext frc = g.getFontRenderContext();
        GlyphVector glyphs = font.createGlyphVector(frc, title);
        java.awt.Shape outline = glyphs.getOutline(40f, 52f);
        float titleWidth = (float) glyphs.getGlyphPosition(title.length()).getX();
        g.setPaint(new GradientPaint(40f, 30f, new Color(0xFFE6A8),
                40f + titleWidth, 52f, new Color(0x9AD7FF)));
        g.fill(outline);

        AttributedString subtitle = new AttributedString(
                "边境星区 · 航线与聚落总览 · FRONTIER CARTOGRAPHY OFFICE");
        subtitle.addAttribute(TextAttribute.FONT,
                new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        subtitle.addAttribute(TextAttribute.FOREGROUND, new Color(0xA7B3D1));
        subtitle.addAttribute(TextAttribute.TRACKING, 0.08f);
        TextLayout layout = new TextLayout(subtitle.getIterator(), frc);
        layout.draw(g, 42, 74);

        g.setColor(new Color(0x7FA7E8));
        g.setStroke(new BasicStroke(1f));
        g.draw(new Line2D.Double(40, 84, w - 40, 84));
    }

    private static void drawLegend(Graphics2D g, LocationGraph graph,
                                   List<Location> places, int w, int h) {
        int panelW = 236;
        int panelH = 170;
        int x = 38;
        int y = h - panelH - 42;
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.72f));
        g.setColor(new Color(0x0A1224));
        g.fill(new RoundRectangle2D.Double(x, y, panelW, panelH, 16, 16));
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(new Color(0x7FA7E8));
        g.setStroke(new BasicStroke(1f));
        Area panel = new Area(new RoundRectangle2D.Double(x, y, panelW, panelH, 16, 16));
        Area cut = new Area(new RoundRectangle2D.Double(x + 5, y + 5,
                panelW - 10, panelH - 10, 12, 12));
        panel.subtract(cut);
        g.fill(panel);

        g.setColor(new Color(0xEAF0FF));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.drawString("图例 / LEGEND", x + 16, y + 24);

        Map<String, Color> entries = new LinkedHashMap<>();
        for (Location place : places) {
            entries.putIfAbsent(kindOf(place), colorOf(place));
        }
        int row = 0;
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
        g.setFont(font);
        for (Map.Entry<String, Color> entry : entries.entrySet()) {
            int ly = y + 46 + row * 20;
            g.setColor(entry.getValue());
            g.fill(new RoundRectangle2D.Double(x + 18, ly - 7, 12, 8, 3, 3));
            g.setColor(new Color(0xCBD8F5));
            g.drawString(entry.getKey(), x + 38, ly);
            row++;
            if (row >= 5) {
                break;
            }
        }
        int statsY = y + panelH - 18;
        g.setColor(new Color(0xA7B3D1));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        g.drawString("地点 " + places.size() + "｜航线 " + graph.links().size()
                + "｜舰船 " + graph.ships().size() + "｜节点 " + graph.nodes().size(),
                x + 16, statsY);
    }

    private static void drawScaleBar(Graphics2D g, int w, int h) {
        int x = w - 300;
        int y = h - 66;
        g.setColor(new Color(0xCBD8F5));
        g.setStroke(new BasicStroke(1.4f));
        g.draw(new Line2D.Double(x, y, x + 180, y));
        for (int i = 0; i <= 4; i++) {
            int tickX = x + i * 45;
            g.draw(new Line2D.Double(tickX, y - 4, tickX, y + 4));
        }
        FontMetrics metrics = g.getFontMetrics(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        g.drawString("0", x - metrics.stringWidth("0") / 2, y + 18);
        g.drawString("2 光日", x + 90 - metrics.stringWidth("2 光日") / 2, y + 18);
        g.drawString("4 光日", x + 180 - metrics.stringWidth("4 光日") / 2, y + 18);
    }

    private static void drawInset(Graphics2D g, BufferedImage image, int w, int h) {
        BufferedImage mini = sharpen(thumbnail(image, Math.min(210, w / 5)));
        int x = w - mini.getWidth() - 44;
        int y = 108;
        g.setColor(new Color(0x0A1224));
        g.fill(new Rectangle2D.Double(x - 6, y - 6, mini.getWidth() + 12,
                mini.getHeight() + 12));
        g.drawImage(mini, x, y, null);
        g.setColor(new Color(0x7FA7E8));
        g.draw(new Rectangle2D.Double(x - 6, y - 6, mini.getWidth() + 12,
                mini.getHeight() + 12));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
        g.setColor(new Color(0xA7B3D1));
        g.drawString("SECTOR INDEX", x, y + mini.getHeight() + 12);
    }

    // ------------------------------------------------------------ 元数据

    private static void applyTextMetadata(IIOMetadata metadata,
                                          Map<String, String> text, String format)
            throws IOException {
        if (text == null || text.isEmpty()) {
            return;
        }
        String nativeFormat = metadata.getNativeMetadataFormatName();
        if (nativeFormat == null) {
            return;
        }
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(nativeFormat);
        if (format.equals("png")) {
            IIOMetadataNode textNode = new IIOMetadataNode("tEXt");
            for (Map.Entry<String, String> entry : text.entrySet()) {
                IIOMetadataNode textEntry = new IIOMetadataNode("tEXtEntry");
                textEntry.setAttribute("keyword", entry.getKey());
                textEntry.setAttribute("value", latin1(entry.getValue()));
                textNode.appendChild(textEntry);
            }
            root.appendChild(textNode);
        } else if (format.equals("jpeg")) {
            NodeList sequences = root.getElementsByTagName("markerSequence");
            if (sequences.getLength() > 0) {
                StringBuilder comment = new StringBuilder();
                for (Map.Entry<String, String> entry : text.entrySet()) {
                    comment.append(entry.getKey()).append('=')
                            .append(latin1(entry.getValue())).append('\n');
                }
                IIOMetadataNode com = new IIOMetadataNode("com");
                com.setAttribute("comment", comment.toString());
                sequences.item(0).appendChild(com);
            }
        }
        try {
            metadata.mergeTree(nativeFormat, root);
        } catch (RuntimeException e) {
            throw new IOException("无法写入 " + format + " 元数据: "
                    + e.getMessage(), e);
        }
    }

    private static String latin1(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            out.append(c <= 0xFF ? c : '?');
        }
        return out.toString();
    }

    // ------------------------------------------------------------ 布局与工具

    private static List<Location> places(LocationGraph graph) {
        List<Location> places = new ArrayList<>();
        for (Location l : graph.nodes().values()) {
            if (!(l instanceof Location.Ship)) {
                places.add(l);
            }
        }
        return places;
    }

    private static int placeCount(LocationGraph graph) {
        return places(graph).size();
    }

    private static Map<Ids.LocationId, Point2D.Double> layout(List<Location> places,
                                                              int w, int h) {
        Map<Ids.LocationId, Point2D.Double> out = new LinkedHashMap<>();
        if (places.isEmpty()) {
            return out;
        }
        double cx = w / 2.0;
        double cy = h / 2.0 + 20;
        double rx = w * 0.34;
        double ry = h * 0.30;
        for (int i = 0; i < places.size(); i++) {
            double angle = -Math.PI / 2 + 2 * Math.PI * i / places.size();
            out.put(places.get(i).id(), new Point2D.Double(
                    cx + rx * Math.cos(angle), cy + ry * Math.sin(angle)));
        }
        return out;
    }

    private static Color colorOf(Location place) {
        return switch (place) {
            case Location.StarSystem s -> new Color(0xFFD166);
            case Location.Planet p -> new Color(0x79D68C);
            case Location.OrbitalStation o -> new Color(0x6EC6FF);
            case Location.Anomaly a -> new Color(0xD98CFF);
            case Location.Colony c -> new Color(0xFF9E64);
            case Location.Facility f -> new Color(0xC0C8D8);
            case Location.ExtensionLocation e -> new Color(0xFF7AA2);
            case Location.Ship s -> new Color(0x9AE6B4);
        };
    }

    private static String kindOf(Location place) {
        return switch (place) {
            case Location.StarSystem s -> "恒星系";
            case Location.Planet p -> "行星";
            case Location.OrbitalStation o -> "轨道站";
            case Location.Anomaly a -> "异常信号";
            case Location.Colony c -> "殖民地";
            case Location.Facility f -> "设施";
            case Location.ExtensionLocation e -> "扩展地点";
            case Location.Ship s -> "舰船";
        };
    }

    private static String truncate(String text, int maxCodePoints) {
        String s = text == null ? "" : text;
        int cp = s.codePointCount(0, s.length());
        if (cp <= maxCodePoints) {
            return s;
        }
        int end = s.offsetByCodePoints(0, maxCodePoints - 1);
        return s.substring(0, end) + "…";
    }

    private static String normalizeFormat(String format) {
        if (format == null || format.isBlank()) {
            throw new IllegalArgumentException("格式不能为空（png/jpeg/jpg）");
        }
        String normalized = format.toLowerCase(Locale.ROOT);
        if (normalized.equals("jpg")) {
            normalized = "jpeg";
        }
        if (!normalized.equals("png") && !normalized.equals("jpeg")) {
            throw new IllegalArgumentException("不支持的格式: " + format + "（可用 png/jpeg）");
        }
        return normalized;
    }
}
