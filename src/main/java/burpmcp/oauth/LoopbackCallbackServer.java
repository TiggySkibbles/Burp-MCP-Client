package burpmcp.oauth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * One-shot local HTTP listener for the OAuth redirect. Per RFC 8252, binds strictly to the
 * loopback interface on an ephemeral port chosen fresh for each authorization attempt.
 */
public final class LoopbackCallbackServer implements AutoCloseable {

    public record CallbackResult(String code, String state, String error, String errorDescription) {
        public boolean isError() {
            return error != null;
        }
    }

    private final HttpServer server;
    private final int port;
    private final CompletableFuture<CallbackResult> resultFuture = new CompletableFuture<>();

    public LoopbackCallbackServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/oauth/callback", this::handle);
        server.setExecutor(null);
        server.start();
    }

    public int port() {
        return port;
    }

    public String redirectUri() {
        return "http://127.0.0.1:" + port + "/oauth/callback";
    }

    public CompletableFuture<CallbackResult> awaitCallback() {
        return resultFuture;
    }

    private void handle(HttpExchange exchange) {
        try {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            String code = query.get("code");
            String state = query.get("state");
            String error = query.get("error");
            String errorDescription = query.get("error_description");

            String html = error != null
                    ? "<html><body><h2>Authorization failed</h2><p>" + escape(error)
                        + (errorDescription != null ? ": " + escape(errorDescription) : "") + "</p><p>You can close this tab.</p></body></html>"
                    : "<html><body><h2>Authorization complete</h2><p>You can close this tab and return to Burp.</p></body></html>";
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            resultFuture.complete(new CallbackResult(code, state, error, errorDescription));
        } catch (Exception e) {
            resultFuture.completeExceptionally(e);
        } finally {
            Thread shutdown = new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                server.stop(0);
            }, "oauth-loopback-shutdown");
            shutdown.setDaemon(true);
            shutdown.start();
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new LinkedHashMap<>();
        if (rawQuery == null) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            if (idx == -1) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            map.put(key, value);
        }
        return map;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
