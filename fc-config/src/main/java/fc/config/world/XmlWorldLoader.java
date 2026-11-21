package fc.config.world;

import fc.config.ConfigException;
import fc.world.ResourceKind;
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
    private static final Set<String> FACTION_KINDS = Set.of("TRADE", "COLONY", "MILITARY", "SCIENCE");

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
        // XSD 校验错误默认只打印告警且继续解析；这里改为严格失败，避免坏配置被静默接受
        b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override
            public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                throw e;
            }

            @Override
            public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                throw e;
            }
        });
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
            BigDecimal credits = e.hasAttribute("credits")
                    ? new BigDecimal(attr(e, "credits")) : BigDecimal.ZERO;
            if (credits.signum() < 0) {
                throw new ConfigException("地点 " + id + " 的 credits 不得为负");
            }
            int level = e.hasAttribute("level") ? Integer.parseInt(attr(e, "level")) : 0;
            if (level < 0 || level > 3) {
                throw new ConfigException("地点 " + id + " 的 level 必须在 0..3");
            }
            String owner = e.hasAttribute("owner") ? attr(e, "owner") : null;
            int population = e.hasAttribute("population")
                    ? Integer.parseInt(attr(e, "population")) : 0;
            if (population < 0) {
                throw new ConfigException("地点 " + id + " 的 population 不得为负");
            }
            String facilityKind = e.hasAttribute("facilityKind") ? attr(e, "facilityKind") : null;
            int workers = e.hasAttribute("workers") ? Integer.parseInt(attr(e, "workers")) : 0;
            if (workers < 0) {
                throw new ConfigException("地点 " + id + " 的 workers 不得为负");
            }
            String knowledge = e.hasAttribute("knowledge") ? attr(e, "knowledge") : null;
            if (knowledge != null && !Set.of("UNKNOWN", "DETECTED", "SURVEYED", "CHARTED")
                    .contains(knowledge)) {
                throw new ConfigException("地点 " + id + " 的 knowledge 非法: " + knowledge);
            }
            locations.add(new WorldDefinition.LocationDef(
                    id, kind, attr(e, "name"), parent, readStock(e), credits,
                    readProduction(e), level, owner, population, readUpgrades(e),
                    facilityKind, workers, knowledge));
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
            if (!Double.isFinite(risk) || risk < 0 || risk > 1) {
                throw new ConfigException("航线 " + from + "->" + to + " 的 risk 必须在 0..1");
            }
            BigDecimal cost = e.hasAttribute("cost") ? new BigDecimal(attr(e, "cost")) : BigDecimal.ZERO;
            if (cost.signum() < 0) {
                throw new ConfigException("航线 " + from + "->" + to + " 的 cost 不得为负");
            }
            links.add(new WorldDefinition.LinkDef(from, to, kind, ticks, risk, cost));
        }

        // 船型表（M4）：货舱、铺位、耐久与基础风险
        List<WorldDefinition.ShipTypeDef> shipTypes = new ArrayList<>();
        Set<String> shipTypeIds = new LinkedHashSet<>();
        for (Element e : children(child(root, "shipTypes"), "shipType")) {
            String typeId = attr(e, "id");
            if (!shipTypeIds.add(typeId)) {
                throw new ConfigException("船型 id 重复: " + typeId);
            }
            BigDecimal cargo = new BigDecimal(attr(e, "cargo"));
            int crew = Integer.parseInt(attr(e, "crew"));
            int hull = Integer.parseInt(attr(e, "hull"));
            double risk = e.hasAttribute("risk") ? Double.parseDouble(attr(e, "risk")) : 0.0;
            if (cargo.signum() <= 0) {
                throw new ConfigException("船型 " + typeId + " 的货舱必须 > 0");
            }
            if (crew < 0 || hull <= 0) {
                throw new ConfigException("船型 " + typeId + " 的 crew/hull 非法");
            }
            if (!Double.isFinite(risk) || risk < 0 || risk > 1) {
                throw new ConfigException("船型 " + typeId + " 的 risk 必须在 0..1");
            }
            shipTypes.add(new WorldDefinition.ShipTypeDef(
                    typeId, attr(e, "name"), cargo, crew, hull, risk));
        }

        List<WorldDefinition.ShipDef> ships = new ArrayList<>();
        Set<Long> shipIds = new LinkedHashSet<>();
        Map<Long, String> shipLocations = new LinkedHashMap<>();
        for (Element e : children(child(root, "ships"), "ship")) {
            long id = Long.parseLong(attr(e, "id"));
            if (!shipIds.add(id)) {
                throw new ConfigException("舰船 id 重复: " + id);
            }
            long at = Long.parseLong(attr(e, "at"));
            if (!ids.contains(at)) {
                throw new ConfigException("舰船 " + id + " 的初始位置不存在: " + at);
            }
            // 舰船作为移动地点进入图，其地点 id 为 shipId + 100000，不得与静态地点冲突
            long locationId = id + 100_000L;
            if (ids.contains(locationId)) {
                throw new ConfigException("舰船 " + id + " 的地点 id " + locationId
                        + " 与静态地点冲突（规则：地点 id = 舰船 id + 100000）");
            }
            String type = e.hasAttribute("type") ? attr(e, "type") : null;
            if (type != null && !shipTypeIds.contains(type)) {
                throw new ConfigException("舰船 " + id + " 的船型不存在: " + type);
            }
            int hull = e.hasAttribute("hull") ? Integer.parseInt(attr(e, "hull")) : 0;
            if (hull < 0) {
                throw new ConfigException("舰船 " + id + " 的 hull 不得为负");
            }
            ships.add(new WorldDefinition.ShipDef(id, attr(e, "name"), at, readCargo(e),
                    type, hull));
            shipLocations.put(locationId, type == null ? "freighter" : type);
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
            long at = resolveAgentLocation(attr(e, "at"), ids, shipIds);
            String trait = e.hasAttribute("trait") ? attr(e, "trait") : "STOIC";
            if (!AGENT_TRAITS.contains(trait)) {
                throw new ConfigException("主体 " + id + " 的 trait 非法: " + trait);
            }
            int baseSkill = e.hasAttribute("skill") ? Integer.parseInt(attr(e, "skill")) : 3;
            checkSkill(id, "skill", baseSkill);
            int pilot = readSkill(e, "pilot", baseSkill, id);
            int engineering = readSkill(e, "engineering", baseSkill, id);
            int research = readSkill(e, "research", baseSkill, id);
            int medical = readSkill(e, "medical", baseSkill, id);
            int trade = readSkill(e, "trade", baseSkill, id);
            int exploration = readSkill(e, "exploration", baseSkill, id);
            agents.add(new WorldDefinition.AgentDef(id, attr(e, "name"), role, at, trait,
                    new fc.world.SkillSet(pilot, engineering, research,
                            medical, trade, exploration)));
        }

        // 势力
        List<WorldDefinition.FactionDef> factions = new ArrayList<>();
        Set<Long> factionIds = new LinkedHashSet<>();
        for (Element e : children(child(root, "factions"), "faction")) {
            long id = Long.parseLong(attr(e, "id"));
            if (!factionIds.add(id)) {
                throw new ConfigException("势力 id 重复: " + id);
            }
            String kind = attr(e, "kind");
            if (!FACTION_KINDS.contains(kind)) {
                throw new ConfigException("势力 " + id + " 的 kind 非法: " + kind);
            }
            long seat = Long.parseLong(attr(e, "seat"));
            if (!ids.contains(seat)) {
                throw new ConfigException("势力 " + id + " 的中心地点不存在: " + seat);
            }
            String priority = attr(e, "priority");
            try {
                ResourceKind.goodsFromId(priority);
            } catch (IllegalArgumentException ex) {
                throw new ConfigException("势力 " + id + " 的 priority 非法: " + priority);
            }
            BigDecimal reserve = e.hasAttribute("reserve")
                    ? new BigDecimal(attr(e, "reserve")) : BigDecimal.ZERO;
            BigDecimal stockTarget = e.hasAttribute("stockTarget")
                    ? new BigDecimal(attr(e, "stockTarget")) : new BigDecimal("50");
            BigDecimal tradePrice = e.hasAttribute("tradeUnitPrice")
                    ? new BigDecimal(attr(e, "tradeUnitPrice")) : new BigDecimal("3");
            BigDecimal taxRate = e.hasAttribute("taxRate")
                    ? new BigDecimal(attr(e, "taxRate")) : BigDecimal.ZERO;
            if (reserve.signum() < 0 || stockTarget.signum() < 0
                    || tradePrice.signum() < 0 || taxRate.signum() < 0) {
                throw new ConfigException("势力 " + id + " 的 reserve/stockTarget/tradeUnitPrice/taxRate 不得为负");
            }
            List<WorldDefinition.RelationDef> relations = new ArrayList<>();
            for (Element r : children(e, "relation")) {
                long other = Long.parseLong(attr(r, "to"));
                int value = Integer.parseInt(attr(r, "value"));
                if (value < -100 || value > 100) {
                    throw new ConfigException("势力关系值必须在 -100..100");
                }
                relations.add(new WorldDefinition.RelationDef(other, value));
            }
            factions.add(new WorldDefinition.FactionDef(id, attr(e, "name"), kind, seat,
                    e.hasAttribute("doctrine") ? attr(e, "doctrine") : "",
                    priority, reserve, stockTarget, tradePrice, taxRate, relations));
        }
        for (WorldDefinition.FactionDef fd : factions) {
            for (WorldDefinition.RelationDef r : fd.relations()) {
                if (!factionIds.contains(r.other())) {
                    throw new ConfigException("势力 " + fd.id() + " 的关系对象不存在: " + r.other());
                }
            }
        }
        for (WorldDefinition.LocationDef loc : locations) {
            if (loc.owner() == null) {
                continue;
            }
            if ("player".equals(loc.owner())) {
                continue;
            }
            if (loc.owner().startsWith("faction:")) {
                long factionId;
                try {
                    factionId = Long.parseLong(loc.owner().substring("faction:".length()));
                } catch (NumberFormatException ex) {
                    throw new ConfigException("地点 " + loc.id() + " 的 owner 非法: " + loc.owner());
                }
                if (!factionIds.contains(factionId)) {
                    throw new ConfigException("地点 " + loc.id() + " 的归属势力不存在: " + loc.owner());
                }
                continue;
            }
            throw new ConfigException("地点 " + loc.id()
                    + " 的 owner 必须是 player 或 faction:<id>，实际: " + loc.owner());
        }

        // 资源经济参数
        List<ResourceDef> resources = new ArrayList<>();
        Set<String> resourceNames = new LinkedHashSet<>();
        for (Element e : children(child(root, "resources"), "resource")) {
            String resName = attr(e, "name");
            try {
                ResourceKind.goodsFromId(resName);
            } catch (IllegalArgumentException ex) {
                throw new ConfigException("资源名必须是 11 种货物之一: " + resName);
            }
            if (!resourceNames.add(resName)) {
                throw new ConfigException("资源重复定义: " + resName);
            }
            BigDecimal base = new BigDecimal(attr(e, "basePrice"));
            if (base.signum() <= 0) {
                throw new ConfigException("资源 " + resName + " 的 basePrice 必须 > 0");
            }
            BigDecimal target = new BigDecimal(attr(e, "target"));
            if (target.signum() <= 0) {
                throw new ConfigException("资源 " + resName + " 的 target 必须 > 0");
            }
            double elasticity = e.hasAttribute("elasticity")
                    ? Double.parseDouble(attr(e, "elasticity")) : 1.0;
            if (!Double.isFinite(elasticity) || elasticity <= 0) {
                throw new ConfigException("资源 " + resName + " 的 elasticity 必须 > 0");
            }
            resources.add(new ResourceDef(resName, base, target, elasticity));
        }

        // 配方
        List<RecipeDef> recipes = new ArrayList<>();
        Set<String> recipeIds = new LinkedHashSet<>();
        for (Element e : children(child(root, "recipes"), "recipe")) {
            String rid = attr(e, "id");
            if (!recipeIds.add(rid)) {
                throw new ConfigException("配方 id 重复: " + rid);
            }
            int ticks = Integer.parseInt(attr(e, "ticks"));
            if (ticks <= 0) {
                throw new ConfigException("配方 " + rid + " 的 ticks 必须 > 0");
            }
            long at = Long.parseLong(attr(e, "at"));
            if (!ids.contains(at)) {
                throw new ConfigException("配方 " + rid + " 的地点不存在: " + at);
            }
            Map<String, BigDecimal> inputs = readAmounts(e, "in");
            Map<String, BigDecimal> outputs = readAmounts(e, "out");
            if (inputs.isEmpty() || outputs.isEmpty()) {
                throw new ConfigException("配方 " + rid + " 必须同时有输入与输出");
            }
            recipes.add(new RecipeDef(rid, attr(e, "name"), ticks, at, inputs, outputs));
        }

        // 事件规则（M4）：参数不再硬编码在 WorldEventStage
        List<WorldDefinition.EventDef> events = new ArrayList<>();
        Set<String> eventIds = new LinkedHashSet<>();
        for (Element e : children(child(root, "events"), "event")) {
            String eventId = attr(e, "id");
            if (!eventIds.add(eventId)) {
                throw new ConfigException("事件 id 重复: " + eventId);
            }
            String kind = attr(e, "kind");
            if (!Set.of("ION_STORM", "PIRACY").contains(kind)) {
                throw new ConfigException("事件 " + eventId + " 的 kind 非法: " + kind);
            }
            double probability = Double.parseDouble(attr(e, "probability"));
            double weatherFactor = e.hasAttribute("weatherFactor")
                    ? Double.parseDouble(attr(e, "weatherFactor")) : 0.0;
            int minSeverity = e.hasAttribute("minSeverity")
                    ? Integer.parseInt(attr(e, "minSeverity")) : 1;
            int maxSeverity = e.hasAttribute("maxSeverity")
                    ? Integer.parseInt(attr(e, "maxSeverity")) : 4;
            int maxDepth = e.hasAttribute("maxDepth")
                    ? Integer.parseInt(attr(e, "maxDepth")) : 3;
            int maxImpacts = e.hasAttribute("maxImpacts")
                    ? Integer.parseInt(attr(e, "maxImpacts")) : 8;
            if (!Double.isFinite(probability) || probability < 0 || probability > 1
                    || !Double.isFinite(weatherFactor) || weatherFactor < 0) {
                throw new ConfigException("事件 " + eventId + " 的 probability/weatherFactor 非法");
            }
            if (minSeverity < 1 || maxSeverity < minSeverity || maxDepth < 0 || maxImpacts < 1) {
                throw new ConfigException("事件 " + eventId + " 的强度/传播参数非法");
            }
            events.add(new WorldDefinition.EventDef(eventId, kind, probability, weatherFactor,
                    minSeverity, maxSeverity, maxDepth, maxImpacts));
        }

        BigDecimal credits = root.hasAttribute("playerCredits")
                ? new BigDecimal(attr(root, "playerCredits")) : BigDecimal.ZERO;
        BigDecimal bankCapital = root.hasAttribute("bankCapital")
                ? new BigDecimal(attr(root, "bankCapital")) : BigDecimal.ZERO;
        BigDecimal loanRate = root.hasAttribute("loanDailyRate")
                ? new BigDecimal(attr(root, "loanDailyRate")) : BigDecimal.ZERO;
        if (credits.signum() < 0 || bankCapital.signum() < 0 || loanRate.signum() < 0) {
            throw new ConfigException("playerCredits/bankCapital/loanDailyRate 不得为负");
        }

        return new WorldDefinition(name, epoch, locations, links, ships, agents, factions,
                new WorldDefinition.EconomyDef(resources, recipes, credits, bankCapital, loanRate),
                seed, shipTypes, events);
    }

    /** 解析主体所在地点：静态地点 id 或 {@code ship:<id>}（映射到 100000 + 舰船 id）。 */
    private static long resolveAgentLocation(String raw, Set<Long> locationIds,
                                             Set<Long> shipIds) {
        if (raw.startsWith("ship:")) {
            long shipId;
            try {
                shipId = Long.parseLong(raw.substring("ship:".length()));
            } catch (NumberFormatException ex) {
                throw new ConfigException("主体所在地点非法: " + raw);
            }
            if (!shipIds.contains(shipId)) {
                throw new ConfigException("主体所在地点的舰船不存在: " + raw);
            }
            return shipId + 100_000L;
        }
        long at;
        try {
            at = Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            throw new ConfigException("主体所在地点非法: " + raw);
        }
        if (!locationIds.contains(at)) {
            if (at > 100_000L && shipIds.contains(at - 100_000L)) {
                return at;
            }
            throw new ConfigException("主体 " + raw + " 的所在地点不存在");
        }
        return at;
    }

    private static int readSkill(Element e, String attr, int fallback, long agentId) {
        if (!e.hasAttribute(attr)) {
            return fallback;
        }
        int value = Integer.parseInt(attr(e, attr));
        checkSkill(agentId, attr, value);
        return value;
    }

    private static void checkSkill(long agentId, String name, int value) {
        if (value < 0 || value > 10) {
            throw new ConfigException("主体 " + agentId + " 的 " + name + " 必须在 0..10");
        }
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
            checkGoods(res, "库存");
            BigDecimal amount = new BigDecimal(attr(item, "amount"));
            if (amount.signum() < 0) {
                throw new ConfigException("库存数量不得为负: " + res + "=" + amount);
            }
            stock.merge(res, amount, BigDecimal::add);
        }
        return stock;
    }

    private static void checkGoods(String resource, String where) {
        try {
            ResourceKind.goodsFromId(resource);
        } catch (IllegalArgumentException ex) {
            throw new ConfigException(where + "资源名非法（信用点不属于库存）: " + resource);
        }
    }

    private static List<WorldDefinition.UpgradeDef> readUpgrades(Element location) {
        List<WorldDefinition.UpgradeDef> upgrades = new ArrayList<>();
        Set<Integer> levels = new LinkedHashSet<>();
        for (Element u : children(location, "upgrade")) {
            int to = Integer.parseInt(attr(u, "level"));
            if (to < 1 || to > 3) {
                throw new ConfigException("殖民地升级目标等级必须在 1..3: " + to);
            }
            if (!levels.add(to)) {
                throw new ConfigException("殖民地升级目标等级重复: " + to);
            }
            Map<String, BigDecimal> goods = new LinkedHashMap<>();
            for (Element need : children(u, "need")) {
                String res = attr(need, "resource");
                checkGoods(res, "升级需求");
                BigDecimal amount = new BigDecimal(attr(need, "amount"));
                if (amount.signum() <= 0) {
                    throw new ConfigException("升级货物数量必须为正: " + res + "=" + amount);
                }
                goods.merge(res, amount, BigDecimal::add);
            }
            BigDecimal credits = new BigDecimal(attr(u, "credits"));
            if (credits.signum() < 0) {
                throw new ConfigException("升级信用点不得为负: " + credits);
            }
            upgrades.add(new WorldDefinition.UpgradeDef(to, goods, credits));
        }
        return upgrades;
    }

    private static WorldDefinition.ProductionDef readProduction(Element location) {
        Element p = child(location, "production");
        if (p == null) {
            return null;
        }
        String resource = attr(p, "resource");
        checkGoods(resource, "每日产出");
        ResourceKind kind = ResourceKind.goodsFromId(resource);
        if (kind != ResourceKind.FOOD && kind != ResourceKind.WATER
                && kind != ResourceKind.ORE && kind != ResourceKind.ENERGY) {
            throw new ConfigException("每日自然产出只适用于食物、水、矿物与能源，不能是 "
                    + resource + "（制成品必须由配方生产）");
        }
        BigDecimal perDay = new BigDecimal(attr(p, "perDay"));
        if (perDay.signum() < 0) {
            throw new ConfigException("每日产出不得为负: " + perDay);
        }
        return new WorldDefinition.ProductionDef(resource, perDay);
    }

    /** 读取 <in>/<out> 资源数量列表。 */
    private static Map<String, BigDecimal> readAmounts(Element parent, String tag) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Element item : children(parent, tag)) {
            String res = attr(item, "resource");
            checkGoods(res, "配方");
            BigDecimal amount = new BigDecimal(attr(item, "amount"));
            if (amount.signum() <= 0) {
                throw new ConfigException("配方资源数量必须 > 0: " + res + "=" + amount);
            }
            out.merge(res, amount, BigDecimal::add);
        }
        return out;
    }

    private static Map<String, BigDecimal> readCargo(Element ship) {
        Map<String, BigDecimal> cargo = new LinkedHashMap<>();
        Element cargoEl = child(ship, "cargo");
        for (Element item : children(cargoEl, "item")) {
            String res = attr(item, "resource");
            checkGoods(res, "货舱");
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
