package fc.config;

import fc.config.world.WorldDefinition;
import fc.config.world.XmlWorldLoader;
import fc.testkit.FcTest;
import fc.testkit.TestRunner;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 世界配置测试：默认世界规模、XSD 结构校验与语义引用完整性。
 */
public final class ConfigTest {

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(ConfigTest.class))) {
            System.exit(1);
        }
    }

    private static final String MINIMAL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <world name="t">
              <locations>
                <location id="1" kind="StarSystem" name="A"/>
              </locations>
              <links/>
              <ships/>
            </world>
            """;

    private static WorldDefinition load(String xml) {
        Path file = null;
        try {
            file = Files.createTempFile("fc-world-", ".xml");
            Files.writeString(file, xml, StandardCharsets.UTF_8);
            return XmlWorldLoader.loadFile(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响断言
                }
            }
        }
    }

    private static void expectInvalid(String xml, String fragment) {
        try {
            load(xml);
            assert false : "非法配置应被拒绝";
        } catch (ConfigException e) {
            assert e.getMessage() != null && !e.getMessage().isBlank() : "错误信息不得为空";
            if (fragment != null) {
                assert e.getMessage().contains(fragment)
                        : "错误信息应包含「" + fragment + "」，实际：" + e.getMessage();
            }
        }
    }

    // ------------------------------------------------------------ 默认世界

    @FcTest
    public void minimalWorldLoads() {
        WorldDefinition w = load(MINIMAL);
        assert w.name().equals("t");
        assert w.locations().size() == 1;
        assert w.links().isEmpty();
        assert w.ships().isEmpty();
        assert w.epochDay() == 0;
        assert w.seed() == WorldDefinition.DEFAULT_SEED;
    }

    @FcTest
    public void defaultWorldMatchesSpecifiedScale() {
        WorldDefinition w = XmlWorldLoader.loadResource("world/default-world.xml");

        assert w.locations().size() == 16 : "地点数 " + w.locations().size();
        assert w.links().size() == 17 : "航线数 " + w.links().size();
        assert w.ships().size() == 3 : "舰船数 " + w.ships().size();
        assert w.agents().size() == 36 : "主体数 " + w.agents().size();
        assert w.factions().size() == 4 : "势力数 " + w.factions().size();
        assert w.economy().resources().size() == 11 : "货物价格定义数";
        assert w.economy().recipes().size() == 8 : "配方数";
        assert w.economy().playerCredits().compareTo(new BigDecimal("1000")) == 0;
        assert w.economy().bankCapital().compareTo(new BigDecimal("5000")) == 0;
        assert w.economy().loanDailyRate().compareTo(new BigDecimal("0.001")) == 0;
        assert w.seed() == 20261120L;
    }

    @FcTest
    public void defaultWorldHasSinglePlayerOutpost() {
        WorldDefinition w = XmlWorldLoader.loadResource("world/default-world.xml");
        long playerColonies = w.locations().stream()
                .filter(l -> "player".equals(l.owner()))
                .count();
        assert playerColonies == 1 : "玩家初始据点应恰好一个，实际 " + playerColonies;
        var outpost = w.locations().stream()
                .filter(l -> "player".equals(l.owner()))
                .findFirst().orElseThrow();
        assert outpost.colonyLevel() == 0 : "起始前哨站为 0 级";
        assert outpost.upgrades().size() == 3 : "前哨站应有三档升级";
    }

    // ------------------------------------------------------------ 结构校验

    @FcTest
    public void malformedXmlIsRejected() {
        expectInvalid("<world name=\"t\"><locations/></world>", "校验失败");
    }

    @FcTest
    public void doctypeIsRejected() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE world [<!ENTITY x "y">]>
                <world name="t"><locations><location id="1" kind="StarSystem" name="A"/>
                </locations><links/><ships/></world>
                """;
        expectInvalid(xml, null);
    }

    @FcTest
    public void invalidAgentRoleIsRejected() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <world name="t">
                  <locations><location id="1" kind="StarSystem" name="A"/></locations>
                  <links/><ships/>
                  <agents><agent id="1" name="甲" role="PILOT" at="1"/></agents>
                </world>
                """;
        expectInvalid(xml, null);
    }

    // ------------------------------------------------------------ 语义校验

    @FcTest
    public void duplicateUpgradeLevelIsRejected() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <world name="t">
                  <locations>
                    <location id="1" kind="Colony" name="C" owner="player" level="0" credits="0">
                      <upgrade level="1" credits="10"><need resource="material" amount="1"/></upgrade>
                      <upgrade level="1" credits="20"><need resource="material" amount="2"/></upgrade>
                    </location>
                  </locations>
                  <links/><ships/>
                </world>
                """;
        expectInvalid(xml, "重复");
    }

    @FcTest
    public void negativeUpgradeCreditsIsRejected() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <world name="t">
                  <locations>
                    <location id="1" kind="Colony" name="C" owner="player" level="0" credits="0">
                      <upgrade level="1" credits="-1"><need resource="material" amount="1"/></upgrade>
                    </location>
                  </locations>
                  <links/><ships/>
                </world>
                """;
        expectInvalid(xml, "信用点不得为负");
    }

    @FcTest
    public void nonPositiveUpgradeGoodsIsRejected() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <world name="t">
                  <locations>
                    <location id="1" kind="Colony" name="C" owner="player" level="0" credits="0">
                      <upgrade level="1" credits="10"><need resource="material" amount="0"/></upgrade>
                    </location>
                  </locations>
                  <links/><ships/>
                </world>
                """;
        expectInvalid(xml, "数量必须为正");
    }

    @FcTest
    public void duplicateLocationIdIsRejected() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <world name="t">
                  <locations>
                    <location id="1" kind="StarSystem" name="A"/>
                    <location id="1" kind="Planet" name="B"/>
                  </locations>
                  <links/><ships/>
                </world>
                """;
        expectInvalid(xml, "重复");
    }

    @FcTest
    public void missingParentIsRejected() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <world name="t">
                  <locations><location id="1" kind="Planet" name="A" parent="9"/></locations>
                  <links/><ships/>
                </world>
                """;
        expectInvalid(xml, "parent");
    }

    @FcTest
    public void missingLinkEndpointIsRejected() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <world name="t">
                  <locations><location id="1" kind="StarSystem" name="A"/></locations>
                  <links><link from="1" to="2" kind="WarpRoute" ticks="1"/></links>
                  <ships/>
                </world>
                """;
        expectInvalid(xml, "端点不存在");
    }

    @FcTest
    public void nonFiniteRiskIsRejected() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <world name="t">
                  <locations>
                    <location id="1" kind="StarSystem" name="A"/>
                    <location id="2" kind="StarSystem" name="B"/>
                  </locations>
                  <links><link from="1" to="2" kind="WarpRoute" ticks="1" risk="NaN"/></links>
                  <ships/>
                </world>
                """;
        expectInvalid(xml, "risk");
    }

    @FcTest
    public void shipIdCollidingWithLocationIsRejected() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <world name="t">
                  <locations>
                    <location id="1" kind="StarSystem" name="A"/>
                    <location id="100001" kind="Anomaly" name="X"/>
                  </locations>
                  <links/>
                  <ships><ship id="1" name="S" at="1"/></ships>
                </world>
                """;
        expectInvalid(xml, "地点 id");
    }

    @FcTest
    public void unknownFactionOwnerIsRejected() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <world name="t">
                  <locations>
                    <location id="1" kind="Colony" name="A" owner="faction:9"/>
                  </locations>
                  <links/><ships/>
                </world>
                """;
        expectInvalid(xml, "归属势力不存在");
    }

    @FcTest
    public void invalidOwnerSyntaxIsRejected() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <world name="t">
                  <locations><location id="1" kind="Colony" name="A" owner="npc"/></locations>
                  <links/><ships/>
                </world>
                """;
        expectInvalid(xml, "owner");
    }

    @FcTest
    public void negativeCreditsAreRejected() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <world name="t">
                  <locations><location id="1" kind="StarSystem" name="A" credits="-1"/></locations>
                  <links/><ships/>
                </world>
                """;
        expectInvalid(xml, "不得为负");
    }

    @FcTest
    public void creditsInStockAreRejected() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <world name="t">
                  <locations>
                    <location id="1" kind="StarSystem" name="A">
                      <stock><item resource="credits" amount="10"/></stock>
                    </location>
                  </locations>
                  <links/><ships/>
                </world>
                """;
        expectInvalid(xml, "信用点不属于库存");
    }

    @FcTest
    public void unknownFactionPriorityIsRejected() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <world name="t">
                  <locations><location id="1" kind="StarSystem" name="A"/></locations>
                  <links/><ships/>
                  <factions>
                    <faction id="1" name="F" kind="TRADE" seat="1" priority="gold"/>
                  </factions>
                </world>
                """;
        expectInvalid(xml, "priority");
    }

    @FcTest
    public void relationToUnknownFactionIsRejected() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <world name="t">
                  <locations><location id="1" kind="StarSystem" name="A"/></locations>
                  <links/><ships/>
                  <factions>
                    <faction id="1" name="F" kind="TRADE" seat="1" priority="food">
                      <relation to="2" value="10"/>
                    </faction>
                  </factions>
                </world>
                """;
        expectInvalid(xml, "关系对象不存在");
    }

    @FcTest
    public void recipeWithoutOutputIsRejected() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <world name="t">
                  <locations><location id="1" kind="StarSystem" name="A"/></locations>
                  <links/><ships/>
                  <recipes>
                    <recipe id="r" name="R" ticks="1" at="1">
                      <in resource="ore" amount="1"/>
                    </recipe>
                  </recipes>
                </world>
                """;
        expectInvalid(xml, "输入与输出");
    }

    @FcTest
    public void manufacturedGoodsCannotUseNaturalProduction() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <world name="t">
                  <locations>
                    <location id="1" kind="Planet" name="A">
                      <production resource="equipment" perDay="1"/>
                    </location>
                  </locations>
                  <links/><ships/>
                </world>
                """;
        expectInvalid(xml, "制成品必须由配方生产");
    }

    @FcTest
    public void missingFileIsRejected() {
        try {
            XmlWorldLoader.loadFile(Path.of("no-such-world-file.xml"));
            assert false : "缺失文件应被拒绝";
        } catch (ConfigException e) {
            assert e.getMessage().contains("找不到世界配置文件") : e.getMessage();
        }
    }
}
