package fc.extensions;

import fc.testkit.FcTest;
import fc.testkit.TestRunner;
import fc.world.Ids;
import fc.world.Link;
import fc.world.Location;
import fc.world.LocationGraph;
import fc.world.Stock;

import javax.imageio.ImageIO;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** M5 外围能力测试：mod 编译/加载、宏、PNG 星图、JMX、HTTP 状态端点和音频降级。 */
public final class ExtensionsTest {

    private static final String MOD_SOURCE = """
            package mods.hello;

            import fc.extensions.FrontierMod;
            import fc.extensions.ModContext;

            public final class HelloMod implements FrontierMod {
                public static int activated;
                public static String greeting = "";

                @Override
                public void activate(ModContext context) {
                    activated = context.settings().size();
                    greeting = context.settings().getOrDefault("greeting", "");
                }
            }
            """;

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(ExtensionsTest.class))) {
            System.exit(1);
        }
    }

    private static Path tempDir() throws Exception {
        return Files.createTempDirectory("fc-ext-test-");
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

    private static Properties descriptor(String id) {
        Properties props = new Properties();
        props.setProperty("id", id);
        props.setProperty("name", "Hello Mod");
        props.setProperty("version", "1.0.0");
        props.setProperty("mainClass", "mods.hello.HelloMod");
        props.setProperty("apiVersion", "1");
        props.setProperty("setting.greeting", "你好");
        props.setProperty("setting.mode", "peaceful");
        return props;
    }

    private static void compileHello(Path output) throws Exception {
        ModCompiler.compileTo(output, "mods.hello.HelloMod", MOD_SOURCE);
    }

    private static Path jarMod(Path dir, Path classes, Properties props) throws Exception {
        Path jar = dir.resolve("hello-mod.jar");
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jarOut = new JarOutputStream(out)) {
            jarOut.putNextEntry(new JarEntry("mod.properties"));
            java.io.ByteArrayOutputStream meta = new java.io.ByteArrayOutputStream();
            props.store(new java.io.OutputStreamWriter(meta, StandardCharsets.UTF_8), null);
            jarOut.write(meta.toByteArray());
            jarOut.closeEntry();
            Path classFile = classes.resolve("mods/hello/HelloMod.class");
            jarOut.putNextEntry(new JarEntry("mods/hello/HelloMod.class"));
            Files.copy(classFile, jarOut);
            jarOut.closeEntry();
        }
        return jar;
    }

    @FcTest
    public void compilerRunsUserSourceInMemory() throws Exception {
        Class<?> calc = ModCompiler.compile("macro.Calc", """
                package macro;
                public final class Calc {
                    public static int twice(int x) { return x * 2; }
                }
                """);
        int result = (int) calc.getMethod("twice", int.class).invoke(null, 21);
        assert result == 42 : result;

        try {
            ModCompiler.compile("macro.Broken", "package macro; class Broken { int x = ; }");
            throw new AssertionError("非法源码应编译失败");
        } catch (ModException expected) {
            assert expected.getMessage().contains("编译失败") : expected.getMessage();
        }
    }

    @FcTest
    public void loaderActivatesExplodedDirectoryMod() throws Exception {
        Path root = tempDir();
        try {
            Path classes = root.resolve("build");
            compileHello(classes);
            Path mod = root.resolve("mods/hello");
            Files.createDirectories(mod);
            try (OutputStream out = Files.newOutputStream(mod.resolve("mod.properties"))) {
                descriptor("hello.mod").store(new java.io.OutputStreamWriter(
                        out, StandardCharsets.UTF_8), null);
            }
            Files.createDirectories(mod.resolve("mods/hello"));
            Files.copy(classes.resolve("mods/hello/HelloMod.class"),
                    mod.resolve("mods/hello/HelloMod.class"));

            ModLoader.LoadReport report = new ModLoader(root.resolve("mods"), false).loadAll();
            assert report.errors().isEmpty() : report.errors();
            assert report.mods().size() == 1;
            ModLoader.LoadedMod loaded = report.mods().get(0);
            assert loaded.descriptor().id().equals("hello.mod");
            assert loaded.descriptor().settings().size() == 2;
            assert loaded.source().getFileName().toString().equals("hello");
            int activated = loaded.mod().getClass().getField("activated").getInt(null);
            String greeting = (String) loaded.mod().getClass().getField("greeting").get(null);
            assert activated == 2 : activated;
            assert "你好".equals(greeting) : greeting;
            loaded.close();
        } finally {
            deleteTree(root);
        }
    }

    @FcTest
    public void loaderRejectsUnsignedJarWhenSignatureRequired() throws Exception {
        Path root = tempDir();
        try {
            Path classes = root.resolve("build");
            compileHello(classes);
            Path mods = root.resolve("mods");
            Files.createDirectories(mods);
            jarMod(mods, classes, descriptor("hello.mod"));

            ModLoader.LoadReport report = new ModLoader(mods, true).loadAll();
            assert report.mods().isEmpty();
            assert report.errors().size() == 1 : report.errors();
            assert report.errors().get(0).contains("签名") : report.errors();

            ModLoader.LoadReport allowed = new ModLoader(mods, false).loadAll();
            assert allowed.errors().isEmpty() : allowed.errors();
            assert allowed.mods().size() == 1;
            allowed.mods().get(0).close();
        } finally {
            deleteTree(root);
        }
    }

    @FcTest
    public void macroExpandsSavesAndRuns() throws Exception {
        Path dir = tempDir();
        try {
            Macro macro = Macro.parse("""
                    # 开局流程
                    @name: 侦察
                    map
                    scan ${ship}
                    sail ${ship} ${dest}
                    """, "fallback");
            assert macro.name().equals("侦察");
            assert macro.steps().size() == 3;
            List<String> expanded = macro.expand(Map.of("ship", "1", "dest", "6"));
            assert expanded.equals(List.of("map", "scan 1", "sail 1 6")) : expanded;
            try {
                macro.expand(Map.of("ship", "1"));
                throw new AssertionError("缺少参数应失败");
            } catch (IllegalArgumentException expected) {
                assert expected.getMessage().contains("dest");
            }

            Path file = dir.resolve("macro.fcmacro");
            macro.save(file);
            Macro loaded = Macro.load(file);
            assert loaded.name().equals("侦察");
            assert loaded.steps().equals(macro.steps());
            List<String> outputs = loaded.run(Map.of("ship", "2", "dest", "3"),
                    cmd -> "ok:" + cmd);
            assert outputs.equals(List.of("ok:map", "ok:scan 2", "ok:sail 2 3")) : outputs;
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void starMapRendersDeterministicPng() throws Exception {
        Path dir = tempDir();
        try {
            Map<Ids.LocationId, Location> nodes = new LinkedHashMap<>();
            nodes.put(new Ids.LocationId(1),
                    new Location.StarSystem(new Ids.LocationId(1), Stock.empty(), "晨曦"));
            nodes.put(new Ids.LocationId(2),
                    new Location.Planet(new Ids.LocationId(2), Stock.empty(), "新伊甸"));
            LocationGraph graph = new LocationGraph(nodes, List.of(
                    new Link.WarpRoute(new Ids.LocationId(1), new Ids.LocationId(2),
                            3, 0.1, new BigDecimal("5"))));
            Path png = StarMapRenderer.render(graph, dir.resolve("map.png"), 640, 480);
            assert Files.size(png) > 1000 : Files.size(png);
            BufferedImage image = ImageIO.read(png.toFile());
            assert image.getWidth() == 640 && image.getHeight() == 480;
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void jmxTelemetryIsRegisteredAndUpdated() throws Exception {
        GameTelemetry telemetry = GameTelemetry.register();
        try {
            telemetry.update(7, "7-03-01", "abc123", 16, 4, "1450", "星门启航", 12);
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(GameTelemetry.OBJECT_NAME);
            assert server.isRegistered(name);
            assert ((Long) server.getAttribute(name, "Day")) == 7L;
            assert "abc123".equals(server.getAttribute(name, "StateHash"));
            assert ((Integer) server.getAttribute(name, "Locations")) == 16;
            assert "星门启航".equals(server.getAttribute(name, "Ending"));
            assert ((Integer) server.getAttribute(name, "CommandCount")) == 12;
        } finally {
            telemetry.close();
        }
        assert !ManagementFactory.getPlatformMBeanServer().isRegistered(
                new ObjectName(GameTelemetry.OBJECT_NAME));
    }

    @FcTest
    public void statusEndpointServesAndParsesJson() throws Exception {
        try (StatusServer server = StatusServer.start(0, () -> StatusServer.json(
                3, "3-01-02", "deadbeef", 16, 4, "900", ""))) {
            String body = StatusServer.fetch(server.url(), Duration.ofSeconds(5));
            assert body.contains("\"day\":3") : body;
            assert body.contains("\"stateHash\":\"deadbeef\"") : body;
            assert body.contains("\"ships\":4") : body;
            assert server.port() > 0;
        }
    }

    @FcTest
    public void soundCapabilityProbeNeverThrows() {
        boolean available = SoundBank.available();
        assert available || !available;
        try {
            SoundBank.playTone(10, 100);
            throw new AssertionError("非法频率应被拒绝");
        } catch (IllegalArgumentException expected) {
            assert expected.getMessage().contains("频率");
        }
        try {
            SoundBank.playTone(440, 5000);
            throw new AssertionError("非法时长应被拒绝");
        } catch (IllegalArgumentException expected) {
            assert expected.getMessage().contains("时长");
        }
    }

    @FcTest
    public void descriptorRejectsBadIdsAndApiVersions() throws Exception {
        Properties props = descriptor("hello.mod");
        props.setProperty("id", "Hello");
        try {
            ModDescriptor.from(props);
            throw new AssertionError("大写 id 应被拒绝");
        } catch (ModException expected) {
            assert expected.getMessage().contains("id");
        }
        props = descriptor("hello.mod");
        props.setProperty("apiVersion", "99");
        try {
            ModDescriptor.from(props);
            throw new AssertionError("API 版本不兼容应被拒绝");
        } catch (ModException expected) {
            assert expected.getMessage().contains("API");
        }
    }
}
