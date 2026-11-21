package fc.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 双窗口模式下的独立命令行窗口。
 *
 * <p>只通过回环套接字连接 {@link SplitConsoleServer}，把命令交给权威宿主执行，
 * 并打印完整输出（不经过 TUI 画布，因此不会被截断）。TUI 窗口只做可视化。</p>
 */
public final class ConsoleClient {

    private ConsoleClient() {
    }

    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = -1;
        String token = "";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = next(args, ++i, args[i]);
                case "--port" -> port = Integer.parseInt(next(args, ++i, args[i]));
                case "--token" -> token = next(args, ++i, args[i]);
                default -> {
                    System.err.println("未知参数：" + args[i]);
                    return;
                }
            }
        }
        if (port <= 0 || token.isEmpty()) {
            System.err.println("用法：ConsoleClient --host 127.0.0.1 --port <端口> --token <令牌>");
            System.exit(2);
        }
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        try (Socket socket = new Socket(host, port);
             BufferedReader in = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8));
             Writer raw = new OutputStreamWriter(socket.getOutputStream(),
                     StandardCharsets.UTF_8);
             BufferedReader keyboard = new BufferedReader(new InputStreamReader(
                     System.in, StandardCharsets.UTF_8))) {
            raw.write(token);
            raw.write('\n');
            raw.flush();

            boolean ready = false;
            for (String line; !ready && (line = in.readLine()) != null; ) {
                if ("READY".equals(line)) {
                    ready = true;
                } else if (line.startsWith("ERROR")) {
                    out.println(line);
                    return;
                } else {
                    out.println(line);
                }
            }
            if (!ready) {
                out.println("连接已关闭。");
                return;
            }
            out.println();
            out.println("提示：输入 help/log/decision 等命令；quit 结束游戏并关闭两个窗口。");
            while (true) {
                out.print("> ");
                out.flush();
                String command = keyboard.readLine();
                if (command == null) {
                    break;
                }
                String trimmed = command.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                raw.write(Base64.getEncoder().encodeToString(
                        trimmed.getBytes(StandardCharsets.UTF_8)));
                raw.write('\n');
                raw.flush();

                String response = in.readLine();
                if (response == null) {
                    out.println("宿主已关闭连接。");
                    break;
                }
                if (response.startsWith("OUT ")) {
                    String encoded = response.substring(4);
                    String text = new String(Base64.getDecoder().decode(encoded),
                            StandardCharsets.UTF_8);
                    out.print(text);
                    if (!text.isEmpty() && !text.endsWith("\n")) {
                        out.println();
                    }
                } else if ("BYE".equals(response)) {
                    out.println("指挥链路已关闭。");
                    break;
                } else {
                    out.println(response);
                }
            }
        } catch (IOException e) {
            out.println("无法连接指挥终端：" + e.getMessage());
            System.exit(1);
        }
    }

    private static String next(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " 需要参数");
        }
        return args[index];
    }
}
