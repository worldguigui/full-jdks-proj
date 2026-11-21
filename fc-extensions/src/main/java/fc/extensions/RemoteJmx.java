package fc.extensions;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.JMException;
import javax.management.MBeanException;
import javax.management.MBeanServer;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.Query;
import javax.management.QueryExp;
import javax.management.ReflectionException;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXServiceURL;
import javax.management.remote.rmi.RMIConnector;
import javax.management.remote.rmi.RMIConnectorServer;
import java.io.IOException;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 回环 JMX 管理端点：把平台 {@link MBeanServer} 暴露到本机 RMI 注册表。
 *
 * <p>只绑定回环地址：RMI 注册表与连接器都使用自定义的
 * {@link RMIServerSocketFactory}/{@link RMIClientSocketFactory}，
 * 服务端只监听 {@link InetAddress#getLoopbackAddress()}，客户端也只连回环，
 * 不依赖 {@code java.rmi.server.hostname}，不做跨机暴露。认证由操作系统本地账户
 * 边界承担；这是一个本地运维入口，不是面向网络的远程管理面。</p>
 *
 * <p>端点 URL 形如 {@code service:jmx:rmi:///jndi/rmi://127.0.0.1:<端口>/jmxrmi}，
 * 可被 {@code jconsole} 或 {@link JMXConnectorFactory} 直接使用。</p>
 */
public final class RemoteJmx {

    /** RMI 注册表中的连接器名称。 */
    public static final String CONNECTOR_NAME = "jmxrmi";

    private RemoteJmx() {
    }

    /** 已启动的管理端点；{@link #close()} 停止连接器并注销 RMI 注册表。 */
    public static final class Server implements AutoCloseable {

        private final int port;
        private final JMXServiceURL url;
        private final JMXConnectorServer connector;
        private final Registry registry;
        private volatile boolean closed;

        private Server(int port, JMXServiceURL url, JMXConnectorServer connector,
                       Registry registry) {
            this.port = port;
            this.url = url;
            this.connector = connector;
            this.registry = registry;
        }

        public int port() {
            return port;
        }

        public JMXServiceURL url() {
            return url;
        }

        public boolean isRunning() {
            return !closed && connector.isActive();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                connector.stop();
            } catch (IOException | RuntimeException e) {
                // 停止失败不应阻止注册表清理。
            }
            try {
                UnicastRemoteObject.unexportObject(registry, true);
            } catch (RemoteException e) {
                // 已经注销。
            }
        }
    }

    /** 启动端点并自动选择空闲端口。 */
    public static Server start() throws IOException {
        return start(0);
    }

    /**
     * 启动回环管理端点。
     *
     * @param requestedPort 0 表示自动选择空闲端口
     */
    public static Server start(int requestedPort) throws IOException {
        if (requestedPort < 0 || requestedPort > 65_535) {
            throw new IllegalArgumentException("端口必须在 0–65535：" + requestedPort);
        }
        int port = requestedPort == 0 ? freePort() : requestedPort;
        LoopbackClient client = new LoopbackClient();
        LoopbackServer server = new LoopbackServer();
        Registry registry;
        try {
            registry = LocateRegistry.createRegistry(port, client, server);
        } catch (RemoteException e) {
            throw new IOException("无法在回环地址创建 RMI 注册表（端口 " + port + "）："
                    + e.getMessage(), e);
        }
        JMXConnectorServer connector = null;
        try {
            Map<String, Object> environment = new HashMap<>();
            // 直接实例化 RMI 连接器（而非经工厂反射选择 provider），同时写入
            // RMI 提供者的标准属性键，确保 stub 与注册表都只使用回环套接字工厂。
            environment.put(RMIClientSocketFactory.class.getName(), client);
            environment.put(RMIServerSocketFactory.class.getName(), server);
            environment.put(RMIConnectorServer.RMI_CLIENT_SOCKET_FACTORY_ATTRIBUTE, client);
            environment.put(RMIConnectorServer.RMI_SERVER_SOCKET_FACTORY_ATTRIBUTE, server);
            JMXServiceURL url = new JMXServiceURL(
                    "service:jmx:rmi:///jndi/rmi://127.0.0.1:" + port + "/" + CONNECTOR_NAME);
            MBeanServer platform = java.lang.management.ManagementFactory
                    .getPlatformMBeanServer();
            connector = new RMIConnectorServer(url, environment, platform);
            connector.start();
            return new Server(port, url, connector, registry);
        } catch (IOException | RuntimeException e) {
            if (connector != null) {
                try {
                    connector.stop();
                } catch (IOException | RuntimeException ignored) {
                    // 清理尽力而为。
                }
            }
            try {
                UnicastRemoteObject.unexportObject(registry, true);
            } catch (RemoteException ignored) {
                // 清理尽力而为。
            }
            throw new IOException("JMX 连接器启动失败：" + e.getMessage(), e);
        }
    }

    /** 连接一个本机管理端点（通用 JMX 工厂，按 URL 协议选择 provider）。 */
    public static JMXConnector connect(JMXServiceURL url) throws IOException {
        if (url == null) {
            throw new IllegalArgumentException("url 不能为空");
        }
        return JMXConnectorFactory.connect(url);
    }

    /** 直接使用 RMI 连接器客户端连接本机端点，绕开工厂查找。 */
    public static JMXConnector directConnect(JMXServiceURL url) throws IOException {
        if (url == null) {
            throw new IllegalArgumentException("url 不能为空");
        }
        JMXConnector connector = new RMIConnector(url, null);
        connector.connect();
        return connector;
    }

    /**
     * 枚举 JMX 域模式下的全部 MBean 名称（按规范名排序）。
     *
     * @param domain 域或域通配模式，如 {@code fc}、{@code fc.*}
     */
    public static Set<ObjectName> allBeans(MBeanServerConnection connection, String domain)
            throws IOException {
        if (connection == null || domain == null || domain.isBlank()
                || domain.contains(":")) {
            throw new IllegalArgumentException("连接与域不能为空，域不含冒号：" + domain);
        }
        try {
            return new TreeSet<>(connection.queryNames(new ObjectName(domain + ":*"), null));
        } catch (javax.management.MalformedObjectNameException e) {
            throw new IOException("JMX 域不合法：" + domain, e);
        }
    }

    /**
     * 用 {@link QueryExp} 在服务端筛选已发布状态的遥测 MBean：
     * {@code StateHash} 属性存在且非空。没有该属性的 MBean 不求值命中。
     */
    public static Set<ObjectName> publishedBeans(MBeanServerConnection connection)
            throws IOException {
        if (connection == null) {
            throw new IllegalArgumentException("connection 不能为空");
        }
        try {
            // Query.match 是字符串模式匹配；Query.gt 只做数值比较，不适用于哈希。
            QueryExp published = Query.match(Query.attr("StateHash"), Query.value("?*"));
            return new TreeSet<>(connection.queryNames(new ObjectName("fc.*:*"), published));
        } catch (javax.management.MalformedObjectNameException e) {
            throw new IOException("遥测查询模式不合法", e);
        }
    }

    /** 读取 {@code fc.telemetry:type=Game} 的只读快照；MBean 未注册时抛 IOException。 */
    public static Map<String, Object> snapshot(MBeanServerConnection connection)
            throws IOException {
        if (connection == null) {
            throw new IllegalArgumentException("connection 不能为空");
        }
        ObjectName name;
        try {
            name = new ObjectName(GameTelemetry.OBJECT_NAME);
        } catch (JMException e) {
            throw new IOException("遥测 MBean 名称非法", e);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (String attribute : List.of("Day", "Now", "StateHash", "Locations", "Ships",
                "Credits", "Ending", "CommandCount")) {
            try {
                values.put(attribute, connection.getAttribute(name, attribute));
            } catch (AttributeNotFoundException | InstanceNotFoundException
                     | MBeanException | ReflectionException e) {
                throw new IOException("遥测属性读取失败（" + attribute + "）："
                        + e.getMessage(), e);
            }
        }
        return values;
    }

    /**
     * 以 {@link Attribute} 形式批量写入；按 JMX 语义返回真正写入的属性，
     * 只读/未知属性会被跳过而不是抛异常。
     */
    public static AttributeList setAttributes(MBeanServerConnection connection, ObjectName name,
                                              Attribute... attributes) throws IOException {
        AttributeList list = new AttributeList();
        for (Attribute attribute : attributes) {
            list.add(attribute);
        }
        try {
            return connection.setAttributes(name, list);
        } catch (InstanceNotFoundException | ReflectionException e) {
            throw new IOException("属性写入失败：" + e.getMessage(), e);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 50,
                InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /** 只连回环地址的 RMI 客户端套接字工厂（可序列化，供 stub 使用）。 */
    static final class LoopbackClient implements RMIClientSocketFactory, Serializable {
        private static final long serialVersionUID = 1L;

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return new Socket(InetAddress.getLoopbackAddress(), port);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof LoopbackClient;
        }

        @Override
        public int hashCode() {
            return LoopbackClient.class.hashCode();
        }
    }

    /** 只监听回环地址的 RMI 服务端套接字工厂（可序列化，供 stub 使用）。 */
    static final class LoopbackServer implements RMIServerSocketFactory, Serializable {
        private static final long serialVersionUID = 1L;

        @Override
        public ServerSocket createServerSocket(int port) throws IOException {
            return new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof LoopbackServer;
        }

        @Override
        public int hashCode() {
            return LoopbackServer.class.hashCode();
        }
    }
}
