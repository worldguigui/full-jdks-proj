package fc.runtime;

import fc.cli.Cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 应用启动器：建立运行会话，打印启动横幅并进入 CLI 交互循环。
 *
 * <p>世界数据全部来自 {@code world.xml}（XSD 校验），经 {@link RuntimeSession}
 * 装配为领域对象并注入系统实现。</p>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        forceUtf8StdoutIfNeeded();

        try {
            RuntimeSession session = RuntimeSession.create(args);
            session.banner().forEach(System.out::println);
            repl(session.cli());
        } catch (fc.config.ConfigException e) {
            System.err.println("启动失败：" + e.getMessage());
            System.exit(2);
        } catch (RuntimeException e) {
            System.err.println("启动失败：" + e.getMessage());
            System.exit(2);
        }
    }

    /**
     * 输出编码处理。
     *
     * <p>Windows 下 JVM 的 stdout 编码默认是控制台代码页（GBK/cp936），中文会乱码；
     * 而 IDEA 控制台通常已是 UTF-8。因此：</p>
     * <ul>
     *   <li>stdout 已是 UTF-8（如 IDEA 控制台）→ 不动，避免二次转码；</li>
     *   <li>否则（Git Bash / 重定向等）→ 显式切换为 UTF-8；</li>
     *   <li>可用 {@code -Dfc.stdout.utf8=false} 关闭本行为（例如在 chcp 936 的原生 cmd 里）。</li>
     * </ul>
     */
    private static void forceUtf8StdoutIfNeeded() {
        if ("false".equalsIgnoreCase(System.getProperty("fc.stdout.utf8", "true"))) {
            return;
        }
        String enc = System.getProperty("stdout.encoding",
                System.getProperty("file.encoding", "UTF-8"));
        if (enc != null && enc.toUpperCase(java.util.Locale.ROOT).contains("UTF")) {
            return;
        }
        System.setOut(new java.io.PrintStream(
                new java.io.FileOutputStream(java.io.FileDescriptor.out),
                false, StandardCharsets.UTF_8));
    }

    private static void repl(Cli cli) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            while (true) {
                System.out.print("> ");
                System.out.flush();
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                String out = cli.execute(line);
                if ("bye".equals(out)) {
                    System.out.println("再见。");
                    break;
                }
                if (!out.isEmpty()) {
                    System.out.println(out);
                }
            }
        } catch (Exception e) {
            System.err.println("REPL 异常: " + e);
        }
    }
}
