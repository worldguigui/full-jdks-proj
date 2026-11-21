package fc.runtime;

import fc.persist.SaveRepository;
import fc.testkit.FcTest;
import fc.testkit.TestRunner;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 双窗口控制台：令牌、命令往返、完整输出与 quit。 */
public final class SplitConsoleTest {

    public static void main(String[] args) {
        boolean passed = TestRunner.report(TestRunner.run(SplitConsoleTest.class));
        // 测试涉及回环服务器/子进程；显式退出，避免非守护线程阻止测试 JVM 结束。
        System.exit(passed ? 0 : 1);
    }

    private record Fixture(SplitConsoleServer server, ConsoleHost host, Path dir,
                           AtomicBoolean stopped) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            server.close();
            try (var walk = Files.walk(dir)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static Fixture fixture() throws IOException {
        Path dir = Files.createTempDirectory("fc-split-");
        AtomicBoolean stopped = new AtomicBoolean();
        ConsoleHost host = new ConsoleHost(RuntimeSession.create(new String[0]),
                new SaveRepository(dir), null, null, () -> stopped.set(true));
        return new Fixture(SplitConsoleServer.start(host), host, dir, stopped);
    }

    private static Socket connect(SplitConsoleServer server, String token) throws IOException {
        Socket socket = new Socket("127.0.0.1", server.port());
        socket.setSoTimeout(5_000);
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.UTF_8));
        out.write(token);
        out.write('\n');
        out.flush();
        return socket;
    }

    private static void awaitReady(BufferedReader in) throws IOException {
        for (int i = 0; i < 40; i++) {
            String line = in.readLine();
            if (line == null) {
                throw new IOException("控制台在 READY 前关闭");
            }
            if ("READY".equals(line)) {
                return;
            }
        }
        throw new IOException("未收到 READY");
    }

    private static String send(BufferedWriter out, BufferedReader in, String command)
            throws IOException {
        out.write(Base64.getEncoder().encodeToString(
                command.getBytes(StandardCharsets.UTF_8)));
        out.write('\n');
        out.flush();
        String response = in.readLine();
        assert response != null && response.startsWith("OUT ") : response;
        return new String(Base64.getDecoder().decode(response.substring(4)),
                StandardCharsets.UTF_8);
    }

    @FcTest
    public void tokenIsRequired() throws Exception {
        try (Fixture fixture = fixture()) {
            try (Socket socket = connect(fixture.server(), "bad-token");
                 BufferedReader in = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8))) {
                assert in.readLine().startsWith("ERROR") : "错误令牌必须被拒绝";
            }
        }
    }

    @FcTest
    public void helpAndLogOutputsArriveComplete() throws Exception {
        try (Fixture fixture = fixture()) {
            try (Socket socket = connect(fixture.server(), fixture.server().token())) {
                BufferedReader in = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.UTF_8));
                awaitReady(in);
                String help = send(out, in, "help");
                assert help.contains("map") && help.contains("quit") : help;
                assert help.lines().count() > 15 : "help 输出不得被截断：" + help;

                String next = send(out, in, "next 3");
                assert !next.isBlank() : next;
                String log = send(out, in, "log all");
                assert log.contains("事件") || log.contains("编年史") : log;
                assert log.lines().count() > 3 : "log 输出不得被截断：" + log;
            }
        }
    }

    @FcTest
    public void consoleClientProcessReceivesFullOutput() throws Exception {
        try (Fixture fixture = fixture()) {
            String java = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name", "").toLowerCase().contains("win")
                            ? "java.exe" : "java").toString();
            Process process = new ProcessBuilder(java, "-Dfile.encoding=UTF-8",
                    "-cp", System.getProperty("java.class.path"),
                    "fc.runtime.ConsoleClient",
                    "--host", "127.0.0.1",
                    "--port", Integer.toString(fixture.server().port()),
                    "--token", fixture.server().token())
                    .redirectErrorStream(true)
                    .start();
            try (var stdin = process.getOutputStream()) {
                stdin.write(("help\nnext 2\nquit\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
            StringBuilder captured = new StringBuilder();
            Thread pump = new Thread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = reader.readLine()) != null; ) {
                        captured.append(line).append('\n');
                    }
                } catch (IOException ignored) {
                    // 进程退出时流关闭。
                }
            }, "console-client-pump");
            pump.setDaemon(true);
            pump.start();
            boolean exited = process.waitFor(15, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
            }
            pump.join(2_000);
            assert exited : "ConsoleClient 未退出";
            String output = captured.toString();
            assert output.contains("FRONTIER CHRONICLE / CLI CONSOLE") : output;
            assert output.contains("指挥链路已关闭") : output;
            assert output.lines().count() > 20 : "独立 CLI 窗口输出不得截断：" + output;
            assert fixture.stopped().get() : "quit 应通知宿主";
        }
    }

    @FcTest
    public void quitStopsHostAndReturnsBye() throws Exception {
        try (Fixture fixture = fixture()) {
            try (Socket socket = connect(fixture.server(), fixture.server().token())) {
                BufferedReader in = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                awaitReady(in);
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.UTF_8));
                out.write(Base64.getEncoder().encodeToString(
                        "quit".getBytes(StandardCharsets.UTF_8)));
                out.write('\n');
                out.flush();
                assert "BYE".equals(in.readLine());
                assert fixture.stopped().get() : "quit 必须通知宿主停止";
            }
        }
    }
}
