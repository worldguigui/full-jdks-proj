package fc.extensions;

import fc.world.Ids;
import fc.world.Link;
import fc.world.Location;
import fc.world.LocationGraph;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把星图渲染为 PNG（java.awt + javax.imageio）。
 *
 * <p>布局是确定性的：静态地点按 id 升序均匀分布在圆周上，链接按端点画直线，
 * 舰船画在母港节点旁的三角形。相同世界图必然生成相同像素布局（文字抗锯齿
 * 依赖平台字体，但几何布局与数据顺序不变），便于截图回归与文档配图。</p>
 *
 * <p>无头环境（CI、服务器）可直接使用 BufferedImage/Graphics2D，无需显示器。</p>
 */
public final class StarMapRenderer {

    private StarMapRenderer() {
    }

    /** 渲染到 PNG 文件；自动创建父目录。 */
    public static Path render(LocationGraph graph, Path output, int width, int height)
            throws IOException {
        if (graph == null) {
            throw new IllegalArgumentException("graph 不能为空");
        }
        if (width < 200 || height < 200) {
            throw new IllegalArgumentException("图像尺寸至少 200×200");
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(0x0B1020));
            g.fillRect(0, 0, width, height);

            List<Location> places = new ArrayList<>();
            for (Location l : graph.nodes().values()) {
                if (!(l instanceof Location.Ship)) {
                    places.add(l);
                }
            }
            Map<Ids.LocationId, java.awt.geom.Point2D.Double> positions = layout(places,
                    width, height);

            g.setColor(new Color(0x44506E));
            g.setStroke(new BasicStroke(1.4f));
            for (Link link : graph.links()) {
                var a = positions.get(link.from());
                var b = positions.get(link.to());
                if (a != null && b != null) {
                    g.drawLine((int) Math.round(a.x), (int) Math.round(a.y),
                            (int) Math.round(b.x), (int) Math.round(b.y));
                }
            }

            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            for (Location place : places) {
                var p = positions.get(place.id());
                if (p == null) {
                    continue;
                }
                int r = 9;
                g.setColor(colorOf(place));
                g.fill(new Ellipse2D.Double(p.x - r, p.y - r, r * 2.0, r * 2.0));
                g.setColor(new Color(0xD8E0FF));
                g.drawString(truncate(place.name(), 18), (float) p.x + 13, (float) p.y + 4);
            }

            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            for (Location.Ship ship : graph.ships()) {
                var p = positions.get(ship.parent());
                if (p == null) {
                    continue;
                }
                double offset = 14 + (ship.shipId().id() % 3) * 7;
                drawShip(g, p.x + offset, p.y - offset);
                g.setColor(new Color(0x9AE6B4));
                g.drawString(truncate(ship.name(), 14), (float) (p.x + offset + 9),
                        (float) (p.y - offset + 4));
            }

            g.setColor(new Color(0xE6ECFF));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
            g.drawString("Frontier Chronicle — 星图", 18, 28);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.setColor(new Color(0xA7B3D1));
            g.drawString("地点 " + places.size() + "｜航线 " + graph.links().size()
                    + "｜舰船 " + graph.ships().size(), 18, 48);
        } finally {
            g.dispose();
        }
        Path normalized = output.toAbsolutePath().normalize();
        if (normalized.getParent() != null) {
            Files.createDirectories(normalized.getParent());
        }
        if (!ImageIO.write(image, "png", normalized.toFile())) {
            throw new IOException("没有可用的 PNG 编码器");
        }
        return normalized;
    }

    private static Map<Ids.LocationId, java.awt.geom.Point2D.Double> layout(
            List<Location> places, int width, int height) {
        Map<Ids.LocationId, java.awt.geom.Point2D.Double> out = new LinkedHashMap<>();
        if (places.isEmpty()) {
            return out;
        }
        double cx = width / 2.0;
        double cy = height / 2.0 + 12;
        double radius = Math.min(width, height) * 0.36;
        for (int i = 0; i < places.size(); i++) {
            double angle = -Math.PI / 2 + 2 * Math.PI * i / places.size();
            out.put(places.get(i).id(), new java.awt.geom.Point2D.Double(
                    cx + radius * Math.cos(angle), cy + radius * Math.sin(angle)));
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

    private static void drawShip(Graphics2D g, double x, double y) {
        Path2D.Double triangle = new Path2D.Double();
        triangle.moveTo(x, y - 7);
        triangle.lineTo(x - 6, y + 6);
        triangle.lineTo(x + 6, y + 6);
        triangle.closePath();
        g.setColor(new Color(0x9AE6B4));
        g.fill(triangle);
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
}
