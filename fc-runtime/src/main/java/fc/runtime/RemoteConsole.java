package fc.runtime;

import fc.tui.Snapshot;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 独立窗口与权威宿主之间的连接：令牌握手、命令往返与只读快照下载。
 *
 * <p>只连接回环地址并使用随机令牌；快照反序列化带类型白名单，不接收
 * 任意类。命令输出与快照都由宿主串行执行/构建。</p>
 */
final class RemoteConsole implements AutoCloseable {

    private final Socket socket;
    private final BufferedReader in;
    private final BufferedWriter out;
    private final List<String> greeting;
    private boolean closed;

    private RemoteConsole(Socket socket, BufferedReader in, BufferedWriter out,
                          List<String> greeting) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.greeting = greeting;
    }

    static RemoteConsole connect(String host, int port, String token) throws IOException {
        Socket socket = new Socket(InetAddress.getByName(host), port);
        socket.setSoTimeout(15_000);
        BufferedReader in = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.UTF_8));
        out.write(token);
        out.write('\n');
        out.flush();
        List<String> greeting = new ArrayList<>();
        for (String line; ; ) {
            line = in.readLine();
            if (line == null) {
                throw new IOException("控制台在握手前关闭");
            }
            if ("READY".equals(line)) {
                break;
            }
            if (line.startsWith("ERROR")) {
                throw new IOException(line);
            }
            greeting.add(line);
        }
        return new RemoteConsole(socket, in, out, greeting);
    }

    List<String> greeting() {
        return List.copyOf(greeting);
    }

    synchronized String command(String command) throws IOException {
        send(command);
        String response = in.readLine();
        if (response == null) {
            throw new IOException("宿主已关闭控制台连接");
        }
        if (response.startsWith("OUT ")) {
            return new String(Base64.getDecoder().decode(response.substring(4)),
                    StandardCharsets.UTF_8);
        }
        if ("BYE".equals(response)) {
            throw new IOException("会话已结束");
        }
        throw new IOException(response);
    }

    synchronized Snapshot snapshot() throws IOException {
        send("@snapshot");
        String response = in.readLine();
        if (response == null || !response.startsWith("SNAP ")) {
            throw new IOException(response == null ? "宿主关闭连接" : response);
        }
        byte[] bytes = Base64.getDecoder().decode(response.substring(5));
        try (ObjectInputStream objects = new ObjectInputStream(
                new ByteArrayInputStream(bytes))) {
            objects.setObjectInputFilter(Filter.INSTANCE);
            return (Snapshot) objects.readObject();
        } catch (ClassNotFoundException | RuntimeException e) {
            throw new IOException("无法读取远端快照：" + e.getMessage(), e);
        }
    }

    synchronized void quit() throws IOException {
        if (closed) {
            return;
        }
        send("quit");
        try {
            in.readLine();
        } catch (IOException ignored) {
            // 关闭时对端可能先断开。
        }
    }

    private void send(String command) throws IOException {
        out.write(Base64.getEncoder().encodeToString(
                command.getBytes(StandardCharsets.UTF_8)));
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
            socket.close();
        } catch (IOException ignored) {
            // 已关闭。
        }
    }

    /** 快照反序列化白名单：只允许本项目视图模型与基础值/集合类型。 */
    private static final class Filter implements ObjectInputFilter {
        static final Filter INSTANCE = new Filter();

        @Override
        public Status checkInput(FilterInfo info) {
            if (info.depth() > 64 || info.references() > 200_000
                    || info.arrayLength() > 200_000) {
                return Status.REJECTED;
            }
            Class<?> type = info.serialClass();
            if (type == null) {
                return Status.UNDECIDED;
            }
            while (type.isArray()) {
                type = type.getComponentType();
            }
            if (type.isPrimitive()) {
                return Status.ALLOWED;
            }
            String name = type.getName();
            boolean allowed = name.startsWith("fc.tui.")
                    || name.startsWith("fc.world.")
                    || name.startsWith("java.util.")
                    || name.startsWith("java.math.")
                    || name.startsWith("java.lang.")
                    || name.startsWith("java.time.");
            return allowed ? Status.ALLOWED : Status.REJECTED;
        }
    }
}
