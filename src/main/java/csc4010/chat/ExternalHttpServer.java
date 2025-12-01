package csc4010.chat;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * HTTP interface so external services/GUI can post chat messages, fetch history, and drive node controls.
 */
public final class ExternalHttpServer implements Closeable {
    public record ExternalMessage(String text, String nickname) {}
    public record HealthSnapshot(String nodeId, String nickname, int peers, int messages) {}
    public record ControlCommand(String action, Map<String, String> params) {}
    public record ControlResult(boolean ok, String message) {}

    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Consumer<ExternalMessage> messageConsumer;
    private final Supplier<List<ChatMessage>> historySupplier;
    private final Supplier<HealthSnapshot> healthSupplier;
    private final Function<ControlCommand, ControlResult> controlHandler;

    public ExternalHttpServer(
            int port,
            Consumer<ExternalMessage> messageConsumer,
            Supplier<List<ChatMessage>> historySupplier,
            Supplier<HealthSnapshot> healthSupplier,
            Function<ControlCommand, ControlResult> controlHandler) throws IOException {
        this.messageConsumer = messageConsumer;
        this.historySupplier = historySupplier;
        this.healthSupplier = healthSupplier;
        this.controlHandler = controlHandler;

        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/chat", new ChatHandler());
        this.server.createContext("/history", new HistoryHandler());
        this.server.createContext("/health", new HealthHandler());
        this.server.createContext("/control", new ControlHandler());
        this.server.setExecutor(executor);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void start() {
        server.start();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private final class ChatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "Only POST supported.");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
            Map<String, String> params = parseParams(body);
            String queryNick = parseQuery(exchange.getRequestURI()).getOrDefault("nick", null);
            String nick = params.getOrDefault("nick", queryNick);
            String text = params.getOrDefault("text", body);
            if (text == null || text.isBlank()) {
                respond(exchange, 400, "Missing chat text.");
                return;
            }
            messageConsumer.accept(new ExternalMessage(text, nick));
            respond(exchange, 200, "OK");
        }
    }

    private final class HistoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "Only GET supported.");
                return;
            }
            List<ChatMessage> snapshot = historySupplier.get();
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < snapshot.size(); i++) {
                ChatMessage message = snapshot.get(i);
                json.append("{\"id\":").append(toJson(message.messageId()))
                        .append(",\"nick\":").append(toJson(message.nickname()))
                        .append(",\"text\":").append(toJson(message.text()))
                        .append(",\"timestamp\":").append(message.timestamp())
                        .append("}");
                if (i + 1 < snapshot.size()) {
                    json.append(',');
                }
            }
            json.append(']');
            respond(exchange, 200, json.toString());
        }
    }

    private final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "Only GET supported.");
                return;
            }
            HealthSnapshot snapshot = healthSupplier.get();
            String payload = """
                    {"nodeId":%s,"nickname":%s,"peers":%d,"messages":%d}
                    """.formatted(
                    toJson(snapshot.nodeId()),
                    toJson(snapshot.nickname()),
                    snapshot.peers(),
                    snapshot.messages());
            respond(exchange, 200, payload);
        }
    }

    private final class ControlHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"ok\":false,\"message\":\"Only POST supported.\"}");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = new LinkedHashMap<>(parseQuery(exchange.getRequestURI()));
            params.putAll(parseParams(body));
            String action = params.remove("action");
            if (action == null || action.isBlank()) {
                respond(exchange, 400, "{\"ok\":false,\"message\":\"Missing action\"}");
                return;
            }
            ControlCommand command = new ControlCommand(action, params);
            ControlResult result = controlHandler.apply(command);
            boolean ok = result != null && result.ok();
            String message = result == null ? "" : result.message();
            String payload = "{\"ok\":" + ok + ",\"message\":" + toJson(message) + "}";
            respond(exchange, ok ? 200 : 400, payload);
        }
    }

    private static Map<String, String> parseParams(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, String> params = new LinkedHashMap<>();
        String[] pairs = raw.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx < 0) {
                continue;
            }
            String key = decode(pair.substring(0, idx));
            String value = decode(pair.substring(idx + 1));
            params.put(key, value);
        }
        if (params.isEmpty()) {
            params.put("text", raw);
        }
        return params;
    }

    private static Map<String, String> parseQuery(URI uri) {
        if (uri == null || uri.getRawQuery() == null) {
            return Collections.emptyMap();
        }
        return parseParams(uri.getRawQuery());
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String toJson(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                + "\"";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
