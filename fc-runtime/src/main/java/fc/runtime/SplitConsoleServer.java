package fc.runtime;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 双窗口模式的本地控制台服务：只绑定回环地址，用随机令牌握手。
 *
 * <p>独立 CLI 进程连接后发送 Base64 编码的命令，宿主执行并把完整输出
 * Base64 返回；输出不经过 TUI 的屏幕缓冲区，因此不会被截断。服务本身
 * 不保存游戏状态，所有命令都由 {@link ConsoleHost} 串行执行。</p>
 */
final class SplitConsoleServer implements AutoCloseable {

    private final ConsoleHost host;
    private final String token;
    private final ServerSocket server;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final Thread acceptor;
    private volatile boolean closed;

    private SplitConsoleServer(ConsoleHost host, String token, ServerSocket server) {
        this.host = host;
        this.token = token;
        this.server = server;
        this.acceptor = new Thread(this::acceptLoop, "fc-console-acceptor");
        this.acceptor.setDaemon(true);
    }

    static SplitConsoleServer start(ConsoleHost host) throws IOException {
        byte[] secret = new byte[18];
        new SecureRandom().nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        ServerSocket server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        SplitConsoleServer result = new SplitConsoleServer(host, token, server);
        result.acceptor.start();
        return result;
    }

    int port() {
        return server.getLocalPort();
    }

    String token() {
        return token;
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = server.accept();
                clients.add(socket);
                Thread worker = new Thread(() -> serve(socket), "fc-console-client");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                if (!closed) {
                    System.err.println("控制台连接失败：" + e.getMessage());
                }
            }
        }
    }

    private void serve(Socket socket) {
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8))) {
            String hello = in.readLine();
            if (hello == null || !token.equals(hello.strip())) {
                write(out, "ERROR 令牌不匹配");
                return;
            }
            write(out, "FRONTIER CHRONICLE / CLI CONSOLE");
            write(out, "命令与完整输出在本窗口；tui on 可在本窗口开启可视化，window 再开窗口。");
            for (String line : host.banner()) {
                write(out, "  " + line);
            }
            write(out, "READY");
            while (!closed) {
                String encoded = in.readLine();
                if (encoded == null) {
                    break;
                }
                String command = new String(Base64.getDecoder().decode(encoded),
                        StandardCharsets.UTF_8).strip();
                if (command.isEmpty()) {
                    write(out, "OUT ");
                    continue;
                }
                if (command.equalsIgnoreCase("quit") || command.equalsIgnoreCase("exit")) {
                    host.stop();
                    write(out, "BYE");
                    break;
                }
                if ("@snapshot".equals(command)) {
                    try {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        try (ObjectOutputStream objects = new ObjectOutputStream(bytes)) {
                            objects.writeObject(host.snapshot());
                        }
                        write(out, "SNAP " + Base64.getEncoder().encodeToString(
                                bytes.toByteArray()));
                    } catch (IOException e) {
                        write(out, "ERROR 快照序列化失败：" + e.getMessage());
                    }
                    continue;
                }
                String output = host.execute(command);
                write(out, "OUT " + Base64.getEncoder().encodeToString(
                        (output == null ? "" : output).getBytes(StandardCharsets.UTF_8)));
            }
        } catch (IOException | IllegalArgumentException e) {
            if (!closed) {
                System.err.println("控制台会话异常：" + e.getMessage());
            }
        } finally {
            clients.remove(socket);
        }
    }

    private static void write(BufferedWriter out, String line) throws IOException {
        out.write(line);
        out.write('\n');
        out.flush();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // 已关闭。
        }
        for (Socket socket : clients) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 尽力关闭。
            }
        }
    }
}
