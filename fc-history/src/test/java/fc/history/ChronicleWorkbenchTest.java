package fc.history;

import fc.history.workbench.ChronicleWorkbench;
import fc.testkit.FcTest;
import fc.testkit.TestRunner;
import fc.world.GameEvent;
import fc.world.Ids;
import fc.world.Location;
import fc.world.LocationGraph;
import fc.world.ResourceLedger;
import fc.world.Stock;
import fc.world.WorldState;
import fc.world.time.Stardate;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.FilteredRowSet;
import javax.sql.rowset.JoinRowSet;
import javax.sql.rowset.WebRowSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 离线编年史工作台：CachedRowSet、过滤、排序、分页、Join 与 WebRowSet XML。 */
public final class ChronicleWorkbenchTest {

    private static final Ids.LocationId L1 = new Ids.LocationId(1);
    private static final Ids.LocationId L2 = new Ids.LocationId(2);
    private static final Ids.LocationId L3 = new Ids.LocationId(3);
    private static final Ids.AgentId A7 = new Ids.AgentId(7);
    private static final Ids.AgentId A9 = new Ids.AgentId(9);

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(ChronicleWorkbenchTest.class))) {
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
                new Ids.ShipId(1), L2, L3, 2));
        chronicle.record(new GameEvent.ShipArrived(new Stardate(4), L3,
                new Ids.ShipId(1), L2));
        return chronicle;
    }

    private static WorldState world() {
        Map<Ids.LocationId, Location> nodes = new LinkedHashMap<>();
        nodes.put(L1, new Location.StarSystem(L1, Stock.empty(), "晨曦"));
        nodes.put(L2, new Location.Planet(L2, Stock.empty(), "新伊甸"));
        nodes.put(L3, new Location.Colony(L3, Stock.empty(), "先驱前哨"));
        return new WorldState(new LocationGraph(nodes, List.of()), Map.of(),
                new ResourceLedger(), new InMemoryChronicle(), new Stardate(9));
    }

    private static List<String> columnNames(CachedRowSet rows) throws SQLException {
        ResultSetMetaData meta = rows.getMetaData();
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            names.add(meta.getColumnName(i));
        }
        return names;
    }

    @FcTest
    public void loadsOfflineRowSetAfterConnectionIsClosed() throws Exception {
        CachedRowSet rows = ChronicleWorkbench.load(sample());
        assert rows.size() == 6 : rows.size();
        assert columnNames(rows).equals(ChronicleWorkbench.COLUMNS) : columnNames(rows);
        // 连接已关闭：仍然可以遍历、定位和读取列。
        rows.last();
        assert rows.getLong("day") == 1L : rows.getLong("day");
        rows.first();
        assert rows.getLong("day") == 4L : "默认按 day 降序：" + rows.getLong("day");
        assert rows.getString("summary") != null;
    }

    @FcTest
    public void filtersByDayTypeAndTextOffline() throws Exception {
        CachedRowSet all = ChronicleWorkbench.load(sample());
        FilteredRowSet storms = ChronicleWorkbench.filter(all,
                new ChronicleWorkbench.Filter(null, null, null, "IonStorm", null, null, null));
        assert ChronicleWorkbench.count(storms) == 1 : ChronicleWorkbench.count(storms);
        FilteredRowSet recent = ChronicleWorkbench.filter(all,
                new ChronicleWorkbench.Filter(2L, 3L, null, null, null, null, null));
        assert ChronicleWorkbench.count(recent) == 3 : ChronicleWorkbench.count(recent);
        FilteredRowSet text = ChronicleWorkbench.filter(all,
                new ChronicleWorkbench.Filter(null, null, null, null, null, null, "metal=5"));
        assert ChronicleWorkbench.count(text) == 1 : ChronicleWorkbench.count(text);
        FilteredRowSet actor = ChronicleWorkbench.filter(all,
                new ChronicleWorkbench.Filter(null, null, null, null, null, "A7", null));
        assert ChronicleWorkbench.count(actor) == 2 : ChronicleWorkbench.count(actor);

        boolean badColumn = false;
        try {
            ChronicleWorkbench.sort(all, "not_a_column", false);
        } catch (SQLException e) {
            badColumn = e.getMessage().contains("未知列");
        }
        assert badColumn : "未知排序列必须拒绝";
    }

    @FcTest
    public void sortsAndPagesOffline() throws Exception {
        CachedRowSet rows = ChronicleWorkbench.load(sample());
        CachedRowSet asc = ChronicleWorkbench.sort(rows, "day", false);
        List<Long> days = new ArrayList<>();
        asc.beforeFirst();
        while (asc.next()) {
            days.add(asc.getLong("day"));
        }
        assert days.equals(List.of(1L, 1L, 2L, 2L, 3L, 4L)) : days;
        assert ChronicleWorkbench.pageCount(asc, 2) == 3;
        String page = ChronicleWorkbench.render(asc, 2, 2);
        assert page.contains("第 2/3 页") : page;
        assert page.contains("共 6 行") : page;
    }

    @FcTest
    public void exportsAndImportsWebRowSetXmlWithVersioning() throws Exception {
        Path dir = Files.createTempDirectory("fc-workbench-");
        try {
            Path file = dir.resolve("chronicle.workbench.xml");
            CachedRowSet rows = ChronicleWorkbench.load(sample());
            ChronicleWorkbench.exportXml(rows, file);
            String xml = Files.readString(file, StandardCharsets.UTF_8);
            assert xml.contains("<webRowSet") : xml;
            assert xml.contains(ChronicleWorkbench.SCHEMA_VERSION) : xml;

            WebRowSet restored = ChronicleWorkbench.importXml(file);
            assert restored.size() == 6 : restored.size();
            assert columnNames(restored).contains(ChronicleWorkbench.SCHEMA_COLUMN)
                    : columnNames(restored);

            Path badVersion = dir.resolve("bad-version.xml");
            Files.writeString(badVersion,
                    xml.replace(ChronicleWorkbench.SCHEMA_VERSION, "fc-workbench/9"),
                    StandardCharsets.UTF_8);
            boolean rejected = false;
            try {
                ChronicleWorkbench.importXml(badVersion);
            } catch (SQLException e) {
                rejected = e.getMessage().contains("不兼容");
            }
            assert rejected : "不兼容版本必须拒绝";

            Path badXml = dir.resolve("bad.xml");
            Files.writeString(badXml, "<not-a-rowset/>", StandardCharsets.UTF_8);
            boolean malformed = false;
            try {
                ChronicleWorkbench.importXml(badXml);
            } catch (SQLException e) {
                malformed = true;
            }
            assert malformed : "非法 XML 必须拒绝";
        } finally {
            try (var walk = Files.walk(dir)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @FcTest
    public void joinsEventsWithLocationCatalog() throws Exception {
        CachedRowSet rows = ChronicleWorkbench.load(sample());
        JoinRowSet joined = ChronicleWorkbench.joinLocations(rows, world());
        assert joined.size() == 6 : "每个事件都应有对应地点：" + joined.size();
        boolean sawName = false;
        joined.beforeFirst();
        while (joined.next()) {
            if ("晨曦".equals(joined.getString("name"))
                    || "新伊甸".equals(joined.getString("name"))
                    || "先驱前哨".equals(joined.getString("name"))) {
                sawName = true;
            }
        }
        assert sawName : "JoinRowSet 应包含地点名称列";
        String rendered = ChronicleWorkbench.render(joined, 1, 20);
        assert rendered.contains("共 6 行") : rendered;
    }
}
