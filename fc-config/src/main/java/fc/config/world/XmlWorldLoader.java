package fc.config.world;

import fc.config.ConfigException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 世界配置装载器：DOM 解析 + XSD 校验 + 引用完整性检查。
 *
 * <p>安全：禁用 DOCTYPE 与外部实体（XXE 防护）；XSD 从类路径 {@code schema/world.xsd} 加载。</p>
 * <p>校验：XSD 负责结构，本类负责语义（id 唯一、航线端点存在、船位存在、数值非负）。</p>
 */
public final class XmlWorldLoader {

    private static final Set<String> LOCATION_KINDS = Set.of(
            "StarSystem", "Planet", "OrbitalStation", "Anomaly", "Colony", "Facility");
    private static final Set<String> LINK_KINDS = Set.of(
            "WarpRoute", "SurfaceRoute", "InternalLane");
    private static final Set<String> AGENT_ROLES = Set.of("CREW", "RESIDENT", "ENVOY");
    private static final Set<String> AGENT_TRAITS = Set.of("DILIGENT", "LAZY", "STOIC", "ANXIOUS");

    private XmlWorldLoader() {
    }

    /** 从类路径资源装载（IDE 直接运行且资源未进 classpath 时，回退到项目内文件路径）。 */
    public static WorldDefinition loadResource(String resourcePath) {
        try (InputStream in = XmlWorldLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in != null) {
                return parse(in, resourcePath);
            }
        } catch (ConfigException e) {
            throw e;
        } catch (Exception e) {
            throw new ConfigException("装载世界配置失败: " + resourcePath + " (" + e + ")");
        }
        // 回退：项目内文件（便于 IDE 单模块直接 Run）
        Path fallback = Path.of("fc-config/src/main/resources", resourcePath);
        if (Files.exists(fallback)) {
            return loadFile(fallback);
        }
        throw new ConfigException("找不到世界配置资源: " + resourcePath
                + "（classpath 与 " + fallback + " 均不存在）");
    }

    /** 从文件路径装载。 */
    public static WorldDefinition loadFile(Path path) {
        if (!Files.exists(path)) {
            throw new ConfigException("找不到世界配置文件: " + path);
        }
        try (InputStream in = Files.newInputStream(path)) {
            return parse(in, path.toString());
        } catch (ConfigException e) {
            throw e;
        } catch (Exception e) {
            throw new ConfigException("装载世界配置失败: " + path + " (" + e + ")");
        }
    }

    private static WorldDefinition parse(InputStream in, String origin) throws Exception {
        Schema schema = loadSchema();
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setSchema(schema);
        // XXE 防护
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);

        DocumentBuilder b = f.newDocumentBuilder();
        Document doc;
        try {
            doc = b.parse(in);
        } catch (org.xml.sax.SAXException e) {
            throw new ConfigException("世界配置校验失败（" + origin + "）：" + e.getMessage());
        }

        Element root = doc.getDocumentElement();
        String name = attr(root, "name");
        long epoch = root.hasAttribute("epoch") ? Long.parseLong(attr(root, "epoch")) : 0L;
        long seed = root.hasAttribute("seed")
                ? Long.parseLong(attr(root, "seed")) : WorldDefinition.DEFAULT_SEED;

        List<WorldDefinition.LocationDef> locations = new ArrayList<>();
        Set<Long> ids = new LinkedHashSet<>();
        for (Element e : children(child(root, "locations"), "location")) {
            long id = Long.parseLong(attr(e, "id"));
            if (!ids.add(id)) {
                throw new ConfigException("地点 id 重复: " + id);
            }
            String kind = attr(e, "kind");
            if (!LOCATION_KINDS.contains(kind)) {
                throw new ConfigException("地点 " + id + " 的 kind 非法: " + kind);
            }
            Long parent = e.hasAttribute("parent") ? Long.valueOf(attr(e, "parent")) : null;
            locations.add(new WorldDefinition.LocationDef(
                    id, kind, attr(e, "name"), parent, readStock(e), readProduction(e)));
        }
        if (locations.isEmpty()) {
            throw new ConfigException("世界配置至少需要一个地点");
        }

        // 父子引用完整性
        for (WorldDefinition.LocationDef loc : locations) {
            if (loc.parentId() != null && !ids.contains(loc.parentId())) {
                throw new ConfigException("地点 " + loc.id() + " 的 parent 不存在: " + loc.parentId());
            }
        }

        List<WorldDefinition.LinkDef> links = new ArrayList<>();
        for (Element e : children(child(root, "links"), "link")) {
            long from = Long.parseLong(attr(e, "from"));
            long to = Long.parseLong(attr(e, "to"));
            if (!ids.contains(from) || !ids.contains(to)) {
                throw new ConfigException("航线端点不存在: " + from + " -> " + to);
            }
            if (from == to) {
                throw new ConfigException("航线端点相同: " + from);
            }
            String kind = attr(e, "kind");
            if (!LINK_KINDS.contains(kind)) {
                throw new ConfigException("航线 " + from + "->" + to + " 的 kind 非法: " + kind);
            }
            int ticks = Integer.parseInt(attr(e, "ticks"));
            if (ticks <= 0) {
                throw new ConfigException("航线 " + from + "->" + to + " 的 ticks 必须 > 0");
            }
            double risk = e.hasAttribute("risk") ? Double.parseDouble(attr(e, "risk")) : 0.0;
            if (risk < 0 || risk > 1) {
                throw new ConfigException("航线 " + from + "->" + to + " 的 risk 必须在 0..1");
            }
            BigDecimal cost = e.hasAttribute("cost") ? new BigDecimal(attr(e, "cost")) : BigDecimal.ZERO;
            if (cost.signum() < 0) {
                throw new ConfigException("航线 " + from + "->" + to + " 的 cost 不得为负");
            }
            links.add(new WorldDefinition.LinkDef(from, to, kind, ticks, risk, cost));
        }

        List<WorldDefinition.ShipDef> ships = new ArrayList<>();
        Set<Long> shipIds = new LinkedHashSet<>();
        for (Element e : children(child(root, "ships"), "ship")) {
            long id = Long.parseLong(attr(e, "id"));
            if (!shipIds.add(id)) {
                throw new ConfigException("舰船 id 重复: " + id);
            }
            long at = Long.parseLong(attr(e, "at"));
            if (!ids.contains(at)) {
                throw new ConfigException("舰船 " + id + " 的初始位置不存在: " + at);
            }
            ships.add(new WorldDefinition.ShipDef(id, attr(e, "name"), at, readCargo(e)));
        }

        List<WorldDefinition.AgentDef> agents = new ArrayList<>();
        Set<Long> agentIds = new LinkedHashSet<>();
        for (Element e : children(child(root, "agents"), "agent")) {
            long id = Long.parseLong(attr(e, "id"));
            if (!agentIds.add(id)) {
                throw new ConfigException("主体 id 重复: " + id);
            }
            String role = attr(e, "role");
            if (!AGENT_ROLES.contains(role)) {
                throw new ConfigException("主体 " + id + " 的 role 非法: " + role
                        + "（可选 " + AGENT_ROLES + "）");
            }
            long at = Long.parseLong(attr(e, "at"));
            if (!ids.contains(at)) {
                throw new ConfigException("主体 " + id + " 的所在地点不存在: " + at);
            }
            String trait = e.hasAttribute("trait") ? attr(e, "trait") : "STOIC";
            if (!AGENT_TRAITS.contains(trait)) {
                throw new ConfigException("主体 " + id + " 的 trait 非法: " + trait);
            }
            int skill = e.hasAttribute("skill") ? Integer.parseInt(attr(e, "skill")) : 3;
            if (skill < 0 || skill > 10) {
                throw new ConfigException("主体 " + id + " 的 skill 必须在 0..10");
            }
            agents.add(new WorldDefinition.AgentDef(id, attr(e, "name"), role, at, trait, skill));
        }

        return new WorldDefinition(name, epoch, locations, links, ships, agents, seed);
    }

    private static Schema loadSchema() throws Exception {
        InputStream xsd = XmlWorldLoader.class.getClassLoader()
                .getResourceAsStream("schema/world.xsd");
        if (xsd == null) {
            // 回退：项目内文件（IDE 单模块直接 Run 时资源可能不在 classpath）
            Path fallback = Path.of("fc-config/src/main/resources/schema/world.xsd");
            if (Files.exists(fallback)) {
                xsd = Files.newInputStream(fallback);
            }
        }
        if (xsd == null) {
            throw new ConfigException("找不到 XSD: schema/world.xsd（classpath 与项目内路径均不存在）");
        }
        try (InputStream in = xsd) {
            SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            return sf.newSchema(new javax.xml.transform.stream.StreamSource(in));
        }
    }

    private static Element child(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getParentNode() == parent) {
                return (Element) n;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static Map<String, BigDecimal> readStock(Element location) {
        Map<String, BigDecimal> stock = new LinkedHashMap<>();
        Element stockEl = child(location, "stock");
        for (Element item : children(stockEl, "item")) {
            String res = attr(item, "resource");
            BigDecimal amount = new BigDecimal(attr(item, "amount"));
            if (amount.signum() < 0) {
                throw new ConfigException("库存数量不得为负: " + res + "=" + amount);
            }
            stock.merge(res, amount, BigDecimal::add);
        }
        return stock;
    }

    private static WorldDefinition.ProductionDef readProduction(Element location) {
        Element p = child(location, "production");
        if (p == null) {
            return null;
        }
        BigDecimal perDay = new BigDecimal(attr(p, "perDay"));
        if (perDay.signum() < 0) {
            throw new ConfigException("每日产出不得为负: " + perDay);
        }
        return new WorldDefinition.ProductionDef(attr(p, "resource"), perDay);
    }

    private static Map<String, BigDecimal> readCargo(Element ship) {
        Map<String, BigDecimal> cargo = new LinkedHashMap<>();
        Element cargoEl = child(ship, "cargo");
        for (Element item : children(cargoEl, "item")) {
            String res = attr(item, "resource");
            BigDecimal amount = new BigDecimal(attr(item, "amount"));
            if (amount.signum() < 0) {
                throw new ConfigException("货舱数量不得为负: " + res + "=" + amount);
            }
            cargo.merge(res, amount, BigDecimal::add);
        }
        return cargo;
    }

    private static String attr(Element e, String name) {
        String v = e.getAttribute(name);
        if (v == null || v.isBlank()) {
            throw new ConfigException("元素 <" + e.getNodeName() + "> 缺少属性 " + name);
        }
        return v;
    }
}
