package fc.history.query;

import fc.world.Chronicle;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.w3c.dom.Document;

/**
 * 编年史导出：CSV、XML、XSLT HTML 与 ZIP 证据包。
 *
 * <p>XML 产出是确定性的：不写入系统时间，只写事件数与稳定排序后的内容。
 * 读取端关闭 DTD、外部实体与外部 schema，避免把导出文件变成 XXE 入口。</p>
 */
public final class ChronicleExport {

    public static final String XML_RESOURCE = "fc/history/chronicle.xsd";
    public static final String XSLT_RESOURCE = "fc/history/chronicle-html.xsl";

    private ChronicleExport() {
    }

    /** RFC 4180 风格 CSV。 */
    public static Path toCsv(Chronicle chronicle, Path output, ChronicleQuery query)
            throws IOException {
        ensureParent(output);
        try (WriterBundle bundle = new WriterBundle(output)) {
            PrintWriter out = bundle.writer;
            out.println("day,date,type,category,location,actors,cause,outcome");
            for (EventView view : query.select(chronicle)) {
                out.println(String.join(",",
                        Long.toString(view.day()),
                        csv(view.date()),
                        csv(view.type()),
                        csv(view.category()),
                        csv(view.location()),
                        csv(view.actorsCsv()),
                        csv(view.cause()),
                        csv(view.outcome())));
            }
        }
        return output;
    }

    /** StAX 写出 XML；结构与 {@code chronicle.xsd} 一致。 */
    public static Path toXml(Chronicle chronicle, Path output, ChronicleQuery query)
            throws IOException {
        ensureParent(output);
        List<EventView> events = query.select(chronicle);
        try (OutputStream raw = Files.newOutputStream(output);
             BufferedOutputStream buffered = new BufferedOutputStream(raw)) {
            XMLStreamWriter xml = XMLOutputFactory.newFactory()
                    .createXMLStreamWriter(buffered, StandardCharsets.UTF_8.name());
            try {
                xml.writeStartDocument("UTF-8", "1.0");
                xml.writeStartElement("chronicle");
                xml.writeAttribute("count", Integer.toString(events.size()));
                for (EventView event : events) {
                    xml.writeStartElement("event");
                    xml.writeAttribute("day", Long.toString(event.day()));
                    xml.writeAttribute("date", event.date());
                    xml.writeAttribute("type", event.type());
                    xml.writeAttribute("category", event.category());
                    xml.writeAttribute("location", event.location());
                    for (String actor : event.actors()) {
                        xml.writeStartElement("actor");
                        xml.writeCharacters(actor);
                        xml.writeEndElement();
                    }
                    element(xml, "cause", event.cause());
                    element(xml, "outcome", event.outcome());
                    xml.writeEndElement();
                }
                xml.writeEndElement();
                xml.writeEndDocument();
            } finally {
                xml.close();
            }
        } catch (XMLStreamException e) {
            throw new IOException("XML 写出失败: " + e.getMessage(), e);
        }
        return output;
    }

    /** 使用内置样式表把 XML 转成人可读 HTML。 */
    public static String toHtml(Chronicle chronicle, Path xmlFile) throws IOException {
        Path xml = toXml(chronicle, xmlFile, ChronicleQuery.all());
        try (InputStream style = resource(XSLT_RESOURCE)) {
            TransformerFactory factory = secureTransformerFactory();
            Templates templates = factory.newTemplates(new StreamSource(style));
            Transformer transformer = templates.newTransformer();
            StringWriter out = new StringWriter();
            transformer.transform(new StreamSource(xml.toFile()), new StreamResult(out));
            return out.toString();
        } catch (TransformerException e) {
            throw new IOException("XSLT 转换失败: " + e.getMessage(), e);
        }
    }

    /** 导出 ZIP 证据包：chronicle.xml、chronicle.csv、chronicle.html、manifest.properties。 */
    public static Path toZip(Chronicle chronicle, Path output, ChronicleQuery query)
            throws IOException {
        ensureParent(output);
        List<EventView> events = query.select(chronicle);
        Path work = Files.createTempDirectory("fc-chronicle-");
        try {
            Path xml = work.resolve("chronicle.xml");
            Path csv = work.resolve("chronicle.csv");
            toXml(chronicle, xml, query);
            toCsv(chronicle, csv, query);
            String html;
            try (InputStream style = resource(XSLT_RESOURCE)) {
                Templates templates = secureTransformerFactory()
                        .newTemplates(new StreamSource(style));
                StringWriter out = new StringWriter();
                templates.newTransformer().transform(
                        new StreamSource(xml.toFile()), new StreamResult(out));
                html = out.toString();
            } catch (TransformerException e) {
                throw new IOException("XSLT 转换失败: " + e.getMessage(), e);
            }

            Properties manifest = new Properties();
            manifest.setProperty("format", "1");
            manifest.setProperty("count", Integer.toString(events.size()));
            manifest.setProperty("generator", "Frontier Chronicle");

            try (OutputStream raw = Files.newOutputStream(output);
                 BufferedOutputStream buffered = new BufferedOutputStream(raw);
                 ZipOutputStream zip = new ZipOutputStream(buffered,
                         StandardCharsets.UTF_8)) {
                put(zip, "chronicle.xml", Files.readAllBytes(xml));
                put(zip, "chronicle.csv", Files.readAllBytes(csv));
                put(zip, "chronicle.html", html.getBytes(StandardCharsets.UTF_8));
                StringWriter props = new StringWriter();
                manifest.store(props, "Frontier Chronicle bundle");
                put(zip, "manifest.properties",
                        props.toString().getBytes(StandardCharsets.ISO_8859_1));
            }
            return output;
        } finally {
            deleteTree(work);
        }
    }

    /** 读取 XML 中的事件数（XPath {@code count(/chronicle/event)}）。 */
    public static int countEvents(Path xmlFile) throws IOException {
        Document doc = parseXml(xmlFile);
        try {
            Object value = XPathFactory.newInstance().newXPath()
                    .evaluate("count(/chronicle/event)", doc, XPathConstants.NUMBER);
            return ((Number) value).intValue();
        } catch (XPathExpressionException e) {
            throw new IOException("XPath 求值失败: " + e.getMessage(), e);
        }
    }

    /** 用 XSD 校验导出文件；失败抛出 IOException。 */
    public static void validateXml(Path xmlFile) throws IOException {
        try (InputStream xsd = resource(XML_RESOURCE)) {
            SchemaFactory factory = SchemaFactory.newInstance(
                    XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Schema schema = factory.newSchema(new StreamSource(xsd));
            Validator validator = schema.newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            try (InputStream in = Files.newInputStream(xmlFile)) {
                validator.validate(new StreamSource(in));
            }
        } catch (Exception e) {
            throw new IOException("XML 校验失败: " + e.getMessage(), e);
        }
    }

    /** 安全解析 XML（禁用 DTD/外部实体）。 */
    public static Document parseXml(Path xmlFile) throws IOException {
        try {
            DocumentBuilderFactory factory = secureBuilderFactory();
            try (InputStream in = Files.newInputStream(xmlFile)) {
                return factory.newDocumentBuilder().parse(in);
            }
        } catch (Exception e) {
            throw new IOException("XML 解析失败: " + e.getMessage(), e);
        }
    }

    /** 安全解析 XML 字符串（测试与内存场景）。 */
    public static Document parseXml(String xml) throws IOException {
        try {
            DocumentBuilderFactory factory = secureBuilderFactory();
            try (StringReader reader = new StringReader(xml)) {
                return factory.newDocumentBuilder().parse(
                        new org.xml.sax.InputSource(reader));
            }
        } catch (Exception e) {
            throw new IOException("XML 解析失败: " + e.getMessage(), e);
        }
    }

    private static DocumentBuilderFactory secureBuilderFactory()
            throws javax.xml.parsers.ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",
                false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(true);
        return factory;
    }

    private static TransformerFactory secureTransformerFactory()
            throws TransformerException {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        return factory;
    }

    private static InputStream resource(String name) throws IOException {
        InputStream in = ChronicleExport.class.getClassLoader().getResourceAsStream(name);
        if (in == null) {
            throw new IOException("缺少资源: " + name);
        }
        return in;
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static void element(XMLStreamWriter xml, String name, String value)
            throws XMLStreamException {
        if (value.isEmpty()) {
            return;
        }
        xml.writeStartElement(name);
        xml.writeCharacters(value);
        xml.writeEndElement();
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        String escaped = value.replace("\"", "\"\"");
        return quote ? "\"" + escaped + "\"" : escaped;
    }

    private static void ensureParent(Path output) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** BufferedWriter 包装，确保 UTF-8 与关闭。 */
    private static final class WriterBundle implements AutoCloseable {
        private final PrintWriter writer;

        private WriterBundle(Path output) throws IOException {
            writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
                    Files.newOutputStream(output), StandardCharsets.UTF_8)));
        }

        @Override
        public void close() {
            writer.close();
        }
    }
}
