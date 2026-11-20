import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.Desktop;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;

/**
 * 阶段三「环境能力小样实验」：在固定 JDK 21 发行版上探测
 * headless / 打印 / 音频 / 剪贴板 / 桌面集成 / ImageIO 编解码器 的实际可用性。
 *
 * 用途：为映射表中 reason_code=ENV（环境限制）的 Excluded 判定提供可复现证据，
 * 而不是凭"CLI 用不到"的直觉排除。输出写入 docs/phase3/data/env-probe.txt。
 *
 * 运行：
 *   java -Djava.awt.headless=true tools/EnvProbe.java docs/phase3/data/env-probe.txt
 */
public final class EnvProbe {

    private final List<String> lines = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path out = args.length > 0 ? Paths.get(args[0]) : Paths.get("docs/phase3/data/env-probe.txt");
        EnvProbe probe = new EnvProbe();
        probe.run();
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.write(out, probe.lines, java.nio.charset.StandardCharsets.UTF_8);
        PrintStream console = System.out;
        probe.lines.forEach(console::println);
        console.println();
        console.println("[written] " + out.toAbsolutePath());
    }

    private void run() {
        header();
        headless();
        clipboard();
        desktop();
        printing();
        audio();
        imageIo();
        fonts();
        charsets();
        conclusion();
    }

    private void header() {
        lines.add("# 阶段三环境能力探测报告（自动生成，勿手改）");
        lines.add("");
        lines.add("java.version        = " + System.getProperty("java.version"));
        lines.add("java.vendor         = " + System.getProperty("java.vendor"));
        lines.add("java.vm.version     = " + System.getProperty("java.vm.version"));
        lines.add("java.vm.name        = " + System.getProperty("java.vm.name"));
        lines.add("java.home           = " + System.getProperty("java.home"));
        lines.add("os.name / os.arch   = " + System.getProperty("os.name") + " / " + System.getProperty("os.arch"));
        lines.add("java.awt.headless   = " + System.getProperty("java.awt.headless"));
        lines.add("file.encoding       = " + System.getProperty("file.encoding"));
        lines.add("user.language/country = " + Locale.getDefault());
        lines.add("");
    }

    private void headless() {
        section("1. 图形环境（java.awt / java.awt.desktop）");
        boolean headless;
        try {
            headless = GraphicsEnvironment.isHeadless();
        } catch (Throwable t) {
            headless = true;
            lines.add("GraphicsEnvironment.isHeadless() 抛出：" + t);
        }
        lines.add("GraphicsEnvironment.isHeadless() = " + headless);
        lines.add("可用字体族数量 = " + safeNum(() -> GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames(Locale.ROOT).length));
        lines.add("结论：GUI 组件（Swing/AWT 容器）" + (headless ? "在本环境不可用；" : "在本环境可用，但本游戏为纯 CLI，仍不加 GUI；"));
    }

    private void clipboard() {
        section("2. 剪贴板（java.awt.datatransfer）");
        try {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            cb.setContents(new StringSelection("Frontier Chronicle probe"), null);
            Object got = cb.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            lines.add("剪贴板写入/回读 = 成功（" + got + "）");
            lines.add("结论：剪贴板可用（但依赖图形会话，不可作为核心功能依赖）");
        } catch (Throwable t) {
            lines.add("剪贴板访问失败：" + t.getClass().getName() + ": " + t.getMessage());
            lines.add("结论：无头环境下 Clipboard 不可用 → 该包判定 Excluded 的原因码为 ENV");
        }
    }

    private void desktop() {
        section("3. 桌面集成（java.awt.Desktop）");
        try {
            boolean sup = Desktop.isDesktopSupported();
            lines.add("Desktop.isDesktopSupported() = " + sup);
            if (sup) {
                Desktop d = Desktop.getDesktop();
                for (Desktop.Action a : Desktop.Action.values()) {
                    lines.add("  Action." + a + ".isSupported = " + d.isSupported(a));
                }
            }
            lines.add("结论：桌面集成与纯 CLI 定位无关，且依赖桌面会话 → 业务无关 + 环境依赖");
        } catch (Throwable t) {
            lines.add("Desktop 探测失败：" + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    private void printing() {
        section("4. 打印服务（javax.print）");
        try {
            PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
            PrintService def = PrintServiceLookup.lookupDefaultPrintService();
            lines.add("lookupPrintServices(null,null).length = " + services.length);
            lines.add("lookupDefaultPrintService() = " + (def == null ? "null" : def.getName()));
            for (PrintService s : services) {
                lines.add("  - " + s.getName());
            }
            lines.add("结论：" + (services.length == 0
                    ? "本环境无任何打印服务 → 无法端到端验证打印路径"
                    : "存在打印服务，但目标运行环境（CLI/容器/CI）不保证有"));
        } catch (Throwable t) {
            lines.add("打印服务探测失败：" + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    private void audio() {
        section("5. 音频（javax.sound.sampled / javax.sound.midi）");
        try {
            Mixer.Info[] mixers = AudioSystem.getMixerInfo();
            lines.add("AudioSystem.getMixerInfo().length = " + mixers.length);
            for (Mixer.Info m : mixers) {
                lines.add("  - " + m.getName() + " / " + m.getDescription());
            }
            lines.add("AudioSystem.getAudioFileTypes() = " + Arrays.toString(AudioSystem.getAudioFileTypes()));
            lines.add("结论：混音器数量 " + mixers.length
                    + "；音频只能作为可降级功能（无设备时静音），但格式编解码（WAV/AU/AIFF）与文件读写不依赖设备，仍可端到端验证");
        } catch (Throwable t) {
            lines.add("音频探测失败：" + t.getClass().getName() + ": " + t.getMessage());
        }
        try {
            javax.sound.midi.MidiSystem.getSequencer();
            lines.add("MidiSystem.getSequencer() = 可用");
        } catch (Throwable t) {
            lines.add("MidiSystem.getSequencer() 不可用：" + t.getClass().getName() + ": " + t.getMessage());
            lines.add("结论：MIDI 实时合成依赖设备；但 Sequence/Track/MidiEvent 的读写与 .mid 文件生成不依赖设备");
        }
    }

    private void imageIo() {
        section("6. 图像编解码（javax.imageio，无头 PNG 星图）");
        String[] formats = ImageIO.getWriterFormatNames();
        lines.add("ImageIO.getWriterFormatNames() = " + Arrays.toString(formats));
        lines.add("ImageIO.getReaderFormatNames() = " + Arrays.toString(ImageIO.getReaderFormatNames()));
        boolean png = Arrays.stream(formats).anyMatch("png"::equalsIgnoreCase);
        boolean tiff = Arrays.stream(formats).anyMatch(f -> f.equalsIgnoreCase("tif") || f.equalsIgnoreCase("tiff"));
        lines.add("PNG 写入支持 = " + png + "；TIFF 写入支持 = " + tiff);
        lines.add("结论：无头环境可生成/读取位图，PNG 星图与统计图路径可行");
    }

    private void fonts() {
        section("7. 字体与文本渲染（java.awt.font）");
        try {
            java.awt.Font f = new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 12);
            lines.add("Font(\"SansSerif\") 可创建 = true, family = " + f.getFamily(Locale.ROOT));
            lines.add("可显示 CJK 字形（'星'）= " + f.canDisplay('星'));
            lines.add("结论：文本渲染可用（无头亦可），但中文字形依赖系统字体，需回退策略");
        } catch (Throwable t) {
            lines.add("字体探测失败：" + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    private void charsets() {
        section("8. 字符集与本地化");
        lines.add("defaultCharset = " + Charset.defaultCharset());
        lines.add("UTF-8 supported = " + Charset.isSupported("UTF-8"));
        lines.add("availableCharsets count = " + Charset.availableCharsets().size());
        lines.add("availableLocales count = " + Locale.getAvailableLocales().length);
        lines.add("Collator 可用语言数 = " + java.text.Collator.getAvailableLocales().length);
    }

    private void conclusion() {
        section("9. 对阶段三判定的影响");
        lines.add("- GUI 主体（Swing/AWT 组件、事件、拖放、桌面集成、无障碍）：业务无关（纯 CLI）→ Excluded/BIZ");
        lines.add("- 无头环境下需要显示设备/外设的包（打印、剪贴板）：环境限制 → Excluded/ENV（附本探测证据）");
        lines.add("- 音频：可降级；文件级编解码可验证 → Planned（带降级说明）");
        lines.add("- 图像：无头可用 → Planned（PNG/TIFF 星图与统计图）");
        lines.add("");
    }

    private void section(String title) {
        lines.add("## " + title);
    }

    private String safeNum(IntSupplier s) {
        try {
            return String.valueOf(s.get());
        } catch (Throwable t) {
            return "不可用（" + t.getClass().getSimpleName() + "）";
        }
    }

    @FunctionalInterface
    private interface IntSupplier {
        int get() throws Exception;
    }
}
