package fc.extensions;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 联机通信第一阶段：只绑定回环地址的聊天室中继（TCP 消息面 + RMI 控制面）。
 *
 * <p>协议是 UTF-8 行文本：客户端先 {@code NICK <昵称>} 注册，随后可发
 * {@code MSG <文本>}、{@code WHO}、{@code HIST [n]}、{@code PING}、{@code QUIT}。
 * 服务器主动推送 {@code OK}/{@code JOIN}/{@code MSG}/{@code PART}/{@code ROSTER}/
 * {@code HISTORY}/{@code PONG}/{@code ERROR} 行；每行一个事实，便于脚本与测试读取。</p>
 *
 * <p>消息面用 {@link ServerSocket} + 虚拟线程处理连接；控制面把成员表与广播能力
 * 通过本机 RMI 注册表暴露为 {@code Control}，供管理工具查询。注册表与控制对象
 * 同样只使用回环套接字工厂，不做跨机暴露。历史消息是进程内最近若干条，不落盘。</p>
 */
public final class ChatRelay implements AutoCloseable {

    /** 昵称最大长度（字符）。 */
    public static final int MAX_NICK = 24;
    /** 单条消息最大长度（字符）。 */
    public static final int MAX_MESSAGE = 240;
    /** 默认保留的历史消息数。 */
    public static final int DEFAULT_HISTORY = 200;
    /** RMI 控制对象在注册表中的名称。 */
    public static final String CONTROL_NAME = "fc-chat-control";

    private final ServerSocket server;
    private final int port;
    private final ExecutorService workers;
    private final ExecutorService acceptor;
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private final Map<Long, Member> members = new ConcurrentHashMap<>();
    private final Map<String, Long> nickIndex = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final AtomicLong sequences = new AtomicLong();
    private final CopyOnWriteArrayList<Message> history = new CopyOnWriteArrayList<>();
    private final int historyLimit;
    private final Registry registry;
    private final int controlPort;
    private volatile Remote registryControl;
    private volatile boolean closed;

    /** 在线成员。 */
    public record Member(long id, String nick, Instant joined) {
    }

    /** 一条聊天消息；{@code id == 0} 表示中继系统消息。 */
    public record Message(long sequence, long id, String nick, String text, Instant at) {
    }

    private ChatRelay(ServerSocket server, int historyLimit, Registry registry,
                      int controlPort) {
        this.server = server;
        this.port = server.getLocalPort();
        this.historyLimit = historyLimit;
        this.registry = registry;
        this.controlPort = controlPort;
        this.workers = Executors.newVirtualThreadPerTaskExecutor();
        this.acceptor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("fc-chat-accept").factory());
    }

    /** 在自动选择的空闲回环端口启动中继。 */
    public static ChatRelay start() throws IOException {
        return start(0, DEFAULT_HISTORY);
    }

    /** 在指定端口启动中继；0 表示自动选择。 */
    public static ChatRelay start(int requestedPort) throws IOException {
        return start(requestedPort, DEFAULT_HISTORY);
    }

    /**
     * 在回环地址启动中继，并在另一回环端口建立 RMI 控制面。
     *
     * @param requestedPort 消息面端口，0 表示自动选择
     * @param historyLimit  保留的历史消息数，必须为正
     */
    public static ChatRelay start(int requestedPort, int historyLimit) throws IOException {
        if (requestedPort < 0 || requestedPort > 65_535) {
            throw new IllegalArgumentException("端口必须在 0–65535：" + requestedPort);
        }
        if (historyLimit <= 0) {
            throw new IllegalArgumentException("历史容量必须为正：" + historyLimit);
        }
        ServerSocket server;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                    requestedPort), 50);
        } catch (IOException e) {
            throw new ChatException("聊天室中继端口不可用（" + requestedPort + "）："
                    + e.getMessage(), e);
        }
        Registry registry = null;
        Remote control = null;
        try {
            int controlPort = freePort();
            registry = LocateRegistry.createRegistry(controlPort,
                    new RemoteJmx.LoopbackClient(), new RemoteJmx.LoopbackServer());
            ChatRelay relay = new ChatRelay(server, historyLimit, registry, controlPort);
            control = UnicastRemoteObject.exportObject(relay.new ControlImpl(),
                    0, new RemoteJmx.LoopbackClient(), new RemoteJmx.LoopbackServer());
            relay.registryControl = control;
            registry.rebind(CONTROL_NAME, control);
            relay.startAccepting();
            return relay;
        } catch (IOException | RuntimeException e) {
            unexport(control);
            unexport(registry);
            try {
                server.close();
            } catch (IOException ignored) {
                // 清理尽力而为。
            }
            throw new ChatException("聊天室中继启动失败：" + e.getMessage(), e);
        }
    }

    private void startAccepting() {
        acceptor.execute(() -> {
            while (!closed) {
                try {
                    Socket socket = server.accept();
                    Session session = new Session(socket);
                    workers.execute(() -> serve(session));
                } catch (IOException e) {
                    if (!closed) {
                        close();
                    }
                    return;
                }
            }
        });
    }

    public int port() {
        return port;
    }

    /** 控制面 RMI 注册表端口。 */
    public int controlPort() {
        return controlPort;
    }

    /** 消息面地址（回环）。 */
    public InetSocketAddress address() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }

    /** 统一资源名，形如 {@code fc://chat/loopback:<端口>}。 */
    public String endpoint() {
        return "fc://chat/loopback:" + port;
    }

    /** 控制面 URL，形如 {@code rmi://127.0.0.1:<端口>/fc-chat-control}。 */
    public String controlEndpoint() {
        return "rmi://127.0.0.1:" + controlPort + "/" + CONTROL_NAME;
    }

    public boolean isRunning() {
        return !closed && !server.isClosed();
    }

    public int memberCount() {
        return members.size();
    }

    public long messageCount() {
        return sequences.get();
    }

    public List<Member> roster() {
        List<Member> list = new ArrayList<>(members.values());
        list.sort((a, b) -> Long.compare(a.id(), b.id()));
        return List.copyOf(list);
    }

    /** 最近 {@code limit} 条消息，按时间正序。 */
    public List<Message> history(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<Message> all = new ArrayList<>(history);
        int from = Math.max(0, all.size() - limit);
        return List.copyOf(all.subList(from, all.size()));
    }

    /**
     * 以中继身份广播一条系统消息。
     *
     * @return 实际送达的连接数
     */
    public int broadcast(String text) throws IOException {
        requireMessage(text);
        return deliver(record(0L, "中继", text));
    }

    /** 连接一个中继；返回的客户端必须关闭。 */
    public Client connect() throws IOException {
        if (!isRunning()) {
            throw new ChatException("聊天室中继未运行");
        }
        return new Client();
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
        for (Session session : List.copyOf(sessions.values())) {
            session.close();
        }
        sessions.clear();
        members.clear();
        nickIndex.clear();
        unexport(registryControl);
        unexport(registry);
        acceptor.shutdownNow();
        workers.shutdownNow();
    }

    private void serve(Session session) {
        try (session) {
            session.write("WELCOME 输入 NICK <昵称> 加入频道");
            String line;
            while (!closed && (line = session.read()) != null) {
                handle(session, line);
                if (session.closed) {
                    return;
                }
            }
        } catch (IOException e) {
            // 连接断开按正常离开处理。
        } finally {
            leave(session, true);
        }
    }

    private void handle(Session session, String line) throws IOException {
        String trimmed = line.strip();
        if (trimmed.isEmpty()) {
            session.write("ERROR 空行");
            return;
        }
        int space = trimmed.indexOf(' ');
        String command = (space < 0 ? trimmed : trimmed.substring(0, space))
                .toUpperCase(Locale.ROOT);
        String argument = space < 0 ? "" : trimmed.substring(space + 1).strip();
        switch (command) {
            case "NICK" -> join(session, argument);
            case "MSG" -> message(session, argument);
            case "WHO" -> sendRoster(session);
            case "HIST" -> sendHistory(session, argument);
            case "PING" -> session.write("PONG " + messageCount());
            case "QUIT" -> {
                session.write("BYE");
                session.close();
            }
            default -> session.write("ERROR 未知命令：" + command);
        }
    }

    private void join(Session session, String nick) throws IOException {
        if (session.member != null) {
            session.write("ERROR 已注册为 " + session.member.nick());
            return;
        }
        if (nick.isEmpty()) {
            session.write("ERROR 昵称不能为空");
            return;
        }
        if (nick.length() > MAX_NICK) {
            session.write("ERROR 昵称过长（最多 " + MAX_NICK + " 字符）");
            return;
        }
        if (nick.chars().anyMatch(Character::isISOControl)) {
            session.write("ERROR 昵称不得包含控制字符");
            return;
        }
        long id = ids.incrementAndGet();
        Long existing = nickIndex.putIfAbsent(nick.toLowerCase(Locale.ROOT), id);
        if (existing != null) {
            session.write("ERROR 昵称已被占用：" + nick);
            return;
        }
        Member member = new Member(id, nick, Instant.now());
        session.member = member;
        sessions.put(id, session);
        members.put(id, member);
        session.write("OK " + id + " " + nick);
        broadcastLine("JOIN " + id + " " + nick + " 在线 " + members.size());
    }

    private void message(Session session, String text) throws IOException {
        if (session.member == null) {
            session.write("ERROR 请先发送 NICK <昵称>");
            return;
        }
        try {
            requireMessage(text);
        } catch (IllegalArgumentException e) {
            session.write("ERROR " + e.getMessage());
            return;
        }
        Message message = record(session.member.id(), session.member.nick(), text);
        deliver(message);
    }

    private void sendRoster(Session session) throws IOException {
        List<Member> roster = roster();
        session.write("ROSTER " + roster.size());
        for (Member member : roster) {
            session.write("USER " + member.id() + " " + member.nick() + " "
                    + member.joined().getEpochSecond());
        }
        session.write("END");
    }

    private void sendHistory(Session session, String argument) throws IOException {
        int limit = DEFAULT_HISTORY;
        if (!argument.isEmpty()) {
            try {
                limit = Integer.parseInt(argument);
            } catch (NumberFormatException e) {
                session.write("ERROR 历史条数必须是整数：" + argument);
                return;
            }
            if (limit < 0) {
                session.write("ERROR 历史条数不能为负");
                return;
            }
        }
        List<Message> recent = history(limit);
        session.write("HISTORY " + recent.size());
        for (Message message : recent) {
            session.write(format(message));
        }
        session.write("END");
    }

    private Message record(long id, String nick, String text) {
        Message message = new Message(sequences.incrementAndGet(), id, nick, text,
                Instant.now());
        history.add(message);
        while (history.size() > historyLimit) {
            history.remove(0);
        }
        return message;
    }

    private int deliver(Message message) {
        String line = format(message);
        int delivered = 0;
        for (Session session : sessions.values()) {
            if (session.member == null || session.closed) {
                continue;
            }
            try {
                session.write(line);
                delivered++;
            } catch (IOException e) {
                session.close();
            }
        }
        return delivered;
    }

    private String format(Message message) {
        return "MSG " + message.sequence() + " " + message.id() + " "
                + message.nick() + " " + message.text();
    }

    private void broadcastLine(String line) {
        for (Session session : sessions.values()) {
            if (session.member == null || session.closed) {
                continue;
            }
            try {
                session.write(line);
            } catch (IOException e) {
                session.close();
            }
        }
    }

    private void leave(Session session, boolean announce) {
        Member member = session.member;
        if (member == null) {
            return;
        }
        sessions.remove(member.id());
        members.remove(member.id());
        nickIndex.remove(member.nick().toLowerCase(Locale.ROOT), member.id());
        if (announce && !closed) {
            broadcastLine("PART " + member.id() + " " + member.nick() + " 在线 "
                    + members.size());
        }
    }

    private static void requireMessage(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        if (text.length() > MAX_MESSAGE) {
            throw new IllegalArgumentException("消息过长（最多 " + MAX_MESSAGE + " 字符）");
        }
        if (text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("消息不得包含控制字符");
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 50,
                InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static void unexport(Remote remote) {
        if (remote == null) {
            return;
        }
        try {
            UnicastRemoteObject.unexportObject(remote, true);
        } catch (Exception ignored) {
            // 已经注销。
        }
    }

    /** 中继侧的一条客户端连接。 */
    private final class Session implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader reader;
        private final Writer writer;
        private final Object writeLock = new Object();
        private volatile Member member;
        private volatile boolean closed;

        Session(Socket socket) throws IOException {
            this.socket = socket;
            this.reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        String read() throws IOException {
            return reader.readLine();
        }

        void write(String line) throws IOException {
            synchronized (writeLock) {
                writer.write(line);
                writer.write('\n');
                writer.flush();
            }
        }

        @Override
        public void close() {
            closed = true;
            try {
                socket.close();
            } catch (IOException ignored) {
                // 已关闭。
            }
        }
    }

    /** 客户端读到的服务端行，带超时。 */
    public final class Client implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader reader;
        private final Writer writer;
        private final Object writeLock = new Object();

        private Client() throws IOException {
            this.socket = new Socket(InetAddress.getLoopbackAddress(), port);
            this.socket.setSoTimeout(5_000);
            this.reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        public void send(String line) throws IOException {
            synchronized (writeLock) {
                writer.write(line);
                writer.write('\n');
                writer.flush();
            }
        }

        /** 读取一行；超过套接字超时返回 {@code null}。 */
        public String readLine() throws IOException {
            try {
                return reader.readLine();
            } catch (SocketTimeoutException e) {
                return null;
            }
        }

        /** 持续读取直到出现 {@code END}，返回包含 END 的全部行。 */
        public List<String> readBlock() throws IOException {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = readLine()) != null) {
                lines.add(line);
                if (line.equals("END")) {
                    return lines;
                }
            }
            return lines;
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 已关闭。
            }
        }
    }

    /** RMI 控制面：成员表、消息计数与系统广播。 */
    public interface Control extends Remote {
        String[] roster() throws RemoteException;

        long messageCount() throws RemoteException;

        int broadcast(String text) throws RemoteException;
    }

    private final class ControlImpl implements Control {
        @Override
        public String[] roster() {
            return ChatRelay.this.roster().stream()
                    .map(member -> member.id() + " " + member.nick() + " "
                            + member.joined().getEpochSecond())
                    .toArray(String[]::new);
        }

        @Override
        public long messageCount() {
            return ChatRelay.this.messageCount();
        }

        @Override
        public int broadcast(String text) throws RemoteException {
            try {
                return ChatRelay.this.broadcast(text);
            } catch (IOException e) {
                throw new RemoteException("广播失败：" + e.getMessage(), e);
            }
        }
    }

    /** 中继失败（端口占用、关闭后连接等）。 */
    public static final class ChatException extends IOException {
        private static final long serialVersionUID = 1L;

        public ChatException(String message) {
            super(message);
        }

        public ChatException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
