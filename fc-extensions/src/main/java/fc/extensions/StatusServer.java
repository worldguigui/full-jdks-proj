package fc.extensions;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * 可选联网：在本机回环地址暴露只读状态端点 {@code /status}。
 *
 * <p>仅绑定 {@link InetAddress#getLoopbackAddress()}，不监听外网；端口传 0 时由
 * 操作系统分配，便于测试。端点返回 UTF-8 JSON，供观战面板、OBS 覆盖层或
 * 外部监控轮询。联网能力完全可选：调用方 {@code catch (IOException)} 后继续
 * 单机运行即可。</p>
 *
 * <p>使用 JDK 内置 {@code jdk.httpserver} 与 {@code java.net.http}，不引入
 * 任何第三方依赖。</p>
 */
public final class StatusServer implements AutoCloseable {

    /** 默认路径。 */
    public static final String PATH = "/status";

    private final HttpServer server;
    private final int port;

    private StatusServer(HttpServer server, int port) {
        this.server = server;
        this.port = port;
    }

    /**
     * 启动服务。
     *
     * @param port 端口；0 表示自动分配（仅回环地址）
     * @param json 每次请求时生成 JSON 的供给器，必须非空
     */
    public static StatusServer start(int port, Supplier<String> json) throws IOException {
        if (json == null) {
            throw new IllegalArgumentException("json 供给器不能为空");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("端口非法: " + port);
        }
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 8);
        server.createContext(PATH, exchange -> {
            try (exchange) {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "GET");
                    respond(exchange, 405, "{\"error\":\"method not allowed\"}");
                    return;
                }
                String body = json.get();
                if (body == null) {
                    body = "{}";
                }
                exchange.getResponseHeaders().set("Content-Type",
                        "application/json; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                respond(exchange, 200, body);
            }
        });
        server.start();
        return new StatusServer(server, server.getAddress().getPort());
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, data.length);
        exchange.getResponseBody().write(data);
    }

    public int port() {
        return port;
    }

    public String url() {
        return "http://127.0.0.1:" + port + PATH;
    }

    /** 拉取状态端点；非 200、超时或网络错误均抛 IOException。 */
    public static String fetch(String url, Duration timeout) throws IOException {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("超时必须为正");
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(timeout)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("状态端点返回 " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("状态请求被中断", e);
        } catch (IllegalArgumentException e) {
            throw new IOException("状态端点 URL 非法: " + url, e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** 生成稳定字段顺序的最小状态 JSON。 */
    public static String json(long day, String now, String hash, int places, int ships,
                              String credits, String ending) {
        return "{" + "\"day\":" + day
                + ",\"now\":" + quote(now)
                + ",\"stateHash\":" + quote(hash)
                + ",\"places\":" + places
                + ",\"ships\":" + ships
                + ",\"credits\":" + quote(credits)
                + ",\"ending\":" + quote(ending)
                + "}";
    }

    private static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
