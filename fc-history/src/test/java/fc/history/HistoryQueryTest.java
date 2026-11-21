package fc.history;

import fc.history.jdbc.ChronicleDataSource;
import fc.history.jdbc.ChronicleDriver;
import fc.history.jdbc.ChronicleSessions;
import fc.history.query.ChronicleExport;
import fc.history.query.ChronicleQuery;
import fc.history.query.EventView;
import fc.testkit.FcTest;
import fc.testkit.TestRunner;
import fc.world.GameEvent;
import fc.world.Ids.AgentId;
import fc.world.Ids.LocationId;
import fc.world.Ids.ShipId;
import fc.world.time.Stardate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipFile;

/** 编年史查询、XML/CSV/XSLT 导出与只读 JDBC 驱动测试。 */
public final class HistoryQueryTest {

    private static final LocationId L1 = new LocationId(1);
    private static final LocationId L2 = new LocationId(2);
    private static final LocationId L3 = new LocationId(3);
    private static final AgentId A7 = new AgentId(7);
    private static final AgentId A9 = new AgentId(9);

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(HistoryQueryTest.class))) {
            System.exit(1);
        }
    }

    private static InMemoryChronicle sample() {
        InMemoryChronicle chronicle = new InMemoryChronicle();
        chronicle.record(new GameEvent.CrewAte(new Stardate(1), L1, A7, 80));
        chronicle.record(new GameEvent.Wedding(A7, A9, new Stardate(1), L2));
        chronicle.record(new GameEvent.IonStorm(new Stardate(2), L1, 3));
        chronicle.record(new GameEvent.ProductionCompleted(new Stardate(2), L3,
                "冶炼", "metal=5"));
        chronicle.record(new GameEvent.ShipDeparted(new Stardate(3), L2,
                new ShipId(1), L2, L3, 2));
        chronicle.record(new GameEvent.ShipArrived(new Stardate(4), L3,
                new ShipId(1), L2));
        return chronicle;
    }

    private static Path tempDir() throws Exception {
        return Files.createTempDirectory("fc-history-test-");
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

    private static List<Long> days(List<EventView> views) {
        List<Long> out = new ArrayList<>();
        for (EventView view : views) {
            out.add(view.day());
        }
        return out;
    }

    @FcTest
    public void queryFiltersByDayCategoryActorAndText() {
        InMemoryChronicle chronicle = sample();
        assert ChronicleQuery.builder().fromDay(2L).toDay(3L).build()
                .select(chronicle).size() == 3;
        assert ChronicleQuery.builder().category("Crew").build()
                .select(chronicle).size() == 2;
        assert ChronicleQuery.builder().actor(7L).build()
                .select(chronicle).size() == 2;
        List<EventView> storm = ChronicleQuery.builder().text("severity=3").build()
                .select(chronicle);
        assert storm.size() == 1 : storm;
        assert storm.get(0).type().equals("IonStorm") : storm;
        assert ChronicleQuery.builder().location("L3").build()
                .select(chronicle).size() == 2;
    }

    @FcTest
    public void queryPaginatesAndOrdersDescending() {
        InMemoryChronicle chronicle = sample();
        List<EventView> all = ChronicleQuery.builder().descending(true).build()
                .select(chronicle);
        assert days(all).equals(List.of(4L, 3L, 2L, 2L, 1L, 1L)) : days(all);
        List<EventView> page = ChronicleQuery.builder().descending(true)
                .offset(1).limit(2).build().select(chronicle);
        assert days(page).equals(List.of(3L, 2L)) : days(page);
        assert ChronicleQuery.builder().descending(true).offset(99).build()
                .select(chronicle).isEmpty();
    }

    @FcTest
    public void csvXmlAndXsdValidationRoundTrip() throws Exception {
        Path dir = tempDir();
        try {
            InMemoryChronicle chronicle = sample();
            Path csv = ChronicleExport.toCsv(chronicle, dir.resolve("out/chronicle.csv"),
                    ChronicleQuery.all());
            String text = Files.readString(csv, StandardCharsets.UTF_8);
            assert text.startsWith("day,date,type,category,location,actors,cause,outcome");
            assert text.contains("CrewAte") : text;
            assert text.contains("2200-01-02") : text;

            Path xml = ChronicleExport.toXml(chronicle, dir.resolve("out/chronicle.xml"),
                    ChronicleQuery.all());
            ChronicleExport.validateXml(xml);
            assert ChronicleExport.countEvents(xml) == 6;
            assert ChronicleExport.parseXml(xml).getDocumentElement()
                    .getAttribute("count").equals("6");

            Path broken = dir.resolve("broken.xml");
            Files.writeString(broken, "<chronicle count=\"1\"><event day=\"1\"/></chronicle>",
                    StandardCharsets.UTF_8);
            boolean rejected = false;
            try {
                ChronicleExport.validateXml(broken);
            } catch (Exception e) {
                rejected = true;
            }
            assert rejected : "缺少必填属性的 XML 应被 XSD 拒绝";
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void xxeAndDoctypeAreRejected() throws Exception {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE foo ["
                + "<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<chronicle count=\"0\">&xxe;</chronicle>";
        boolean rejected = false;
        try {
            ChronicleExport.parseXml(xxe);
        } catch (Exception e) {
            rejected = true;
        }
        assert rejected : "DTD/外部实体必须被拒绝";
    }

    @FcTest
    public void xsltProducesHtmlReport() throws Exception {
        Path dir = tempDir();
        try {
            String html = ChronicleExport.toHtml(sample(), dir.resolve("chronicle.xml"));
            assert html.contains("边境纪事") : html;
            assert html.contains("CrewAte") : html;
            assert html.contains("<table") : html;
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void zipBundleContainsXmlCsvHtmlAndManifest() throws Exception {
        Path dir = tempDir();
        try {
            Path zip = ChronicleExport.toZip(sample(), dir.resolve("bundle.zip"),
                    ChronicleQuery.builder().category("Fleet").build());
            try (ZipFile file = new ZipFile(zip.toFile())) {
                assert file.getEntry("chronicle.xml") != null;
                assert file.getEntry("chronicle.csv") != null;
                assert file.getEntry("chronicle.html") != null;
                assert file.getEntry("manifest.properties") != null;
                try (InputStream in = file.getInputStream(file.getEntry("chronicle.xml"))) {
                    String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    assert xml.contains("ShipDeparted") : xml;
                    assert !xml.contains("CrewAte") : xml;
                }
            }
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void jdbcDriverManagerSelectsEventsAndMetadata() throws Exception {
        ChronicleSessions.bind("test", sample());
        try {
            Class.forName("fc.history.jdbc.ChronicleDriver");
            try (Connection connection =
                         DriverManager.getConnection("jdbc:fc:chronicle:test")) {
                assert connection.isReadOnly() : "只读驱动";
                try (Statement statement = connection.createStatement();
                     ResultSet rs = statement.executeQuery(
                             "SELECT day, type, category, location, actors FROM chronicle "
                                     + "WHERE day >= 1 AND category = 'Crew' "
                                     + "ORDER BY day DESC LIMIT 5")) {
                    ResultSetMetaData meta = rs.getMetaData();
                    assert meta.getColumnCount() == 5 : meta.getColumnCount();
                    assert meta.getColumnName(2).equals("type") : meta.getColumnName(2);
                    assert rs.next();
                    assert rs.getLong("day") == 1 : rs.getLong("day");
                    assert rs.getString(2).equals("Wedding")
                            || rs.getString(2).equals("CrewAte") : rs.getString(2);
                    assert rs.next();
                    assert !rs.next();
                }
                String product = connection.getMetaData().getDatabaseProductName();
                assert product.equals("Frontier Chronicle") : product;
            }
        } finally {
            ChronicleSessions.unbind("test");
        }
    }

    @FcTest
    public void jdbcPreparedLikeCountAndWriteRejection() throws Exception {
        ChronicleSessions.bind("prep", sample());
        try {
            try (Connection connection = ChronicleDriver.connectTo("prep")) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT type, day FROM chronicle WHERE type LIKE ? AND day >= ? "
                                + "ORDER BY day")) {
                    statement.setString(1, "Crew%");
                    statement.setInt(2, 0);
                    try (ResultSet rs = statement.executeQuery()) {
                        assert rs.next() : "应命中船员事件";
                        assert rs.getString("type").startsWith("Crew") : rs.getString(1);
                    }
                }
                try (Statement statement = connection.createStatement();
                     ResultSet rs = statement.executeQuery(
                             "SELECT COUNT(*) AS n FROM chronicle WHERE day >= 2")) {
                    assert rs.next();
                    assert rs.getInt("n") == 4 : rs.getInt("n");
                }
                boolean rejected = false;
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("DELETE FROM chronicle");
                } catch (SQLFeatureNotSupportedException e) {
                    rejected = true;
                }
                assert rejected : "只读驱动必须拒绝写入";
            }
        } finally {
            ChronicleSessions.unbind("prep");
        }
    }

    @FcTest
    public void jdbcDataSourceAndUnboundSessionErrors() throws Exception {
        ChronicleSessions.bind("ds", sample());
        try {
            ChronicleDataSource source = new ChronicleDataSource("ds");
            try (Connection connection = source.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT COUNT(*) FROM chronicle")) {
                assert rs.next();
                assert rs.getInt(1) == 6 : rs.getInt(1);
            }
            boolean unbound = false;
            try {
                ChronicleDriver.connectTo("missing");
            } catch (SQLException e) {
                unbound = true;
            }
            assert unbound;
        } finally {
            ChronicleSessions.unbind("ds");
        }
    }

    @FcTest
    public void invalidSqlIsRejected() throws Exception {
        ChronicleSessions.bind("bad", sample());
        try (Connection connection = ChronicleDriver.connectTo("bad");
             Statement statement = connection.createStatement()) {
            boolean badColumn = false;
            try {
                statement.executeQuery("SELECT nope FROM chronicle");
            } catch (SQLException e) {
                badColumn = true;
            }
            assert badColumn;
            boolean badTail = false;
            try {
                statement.executeQuery("SELECT * FROM chronicle WHERE");
            } catch (SQLException e) {
                badTail = true;
            }
            assert badTail;
        } finally {
            ChronicleSessions.unbind("bad");
        }
    }
}
