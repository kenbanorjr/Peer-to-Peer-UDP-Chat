
package csc4010.chat;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Minimal Swing GUI that talks to the HTTP interface exposed by ChatNode.
 */
public final class ChatGuiApp {
    private ChatGuiApp() {
    }

    public static void main(String[] args) {
        GuiConfig config = GuiConfig.fromArgs(args);
        SwingUtilities.invokeLater(() -> new ChatGuiFrame(config).showWindow());
    }

    private record GuiConfig(URI server, String nickname, int refreshSeconds) {
        private static GuiConfig fromArgs(String[] args) {
            URI server = URI.create("http://localhost:8080");
            String nickname = "GuiPeer";
            int refresh = 2;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) {
                    continue;
                }
                switch (arg) {
                    case "--server" -> {
                        ensureValue(args, i, arg);
                        server = normalizeUri(args[++i]);
                    }
                    case "--nick" -> {
                        ensureValue(args, i, arg);
                        nickname = args[++i];
                    }
                    case "--refresh" -> {
                        ensureValue(args, i, arg);
                        refresh = Math.max(1, Integer.parseInt(args[++i]));
                    }
                    case "--help", "-h" -> {
                        printUsageAndExit();
                    }
                    default -> throw new IllegalArgumentException("Unknown flag " + arg);
                }
            }
            return new GuiConfig(server, nickname, refresh);
        }

        private static void ensureValue(String[] args, int index, String flag) {
            if (index + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + flag);
            }
        }

        private static URI normalizeUri(String raw) {
            URI uri = URI.create(raw);
            if (uri.getScheme() == null) {
                throw new IllegalArgumentException("Server URI must include scheme, e.g. http://host:port");
            }
            return uri;
        }

        private static void printUsageAndExit() {
            System.out.println("""
                    GUI client for the distributed chat HTTP API.
                    Usage: java csc4010.chat.ChatGuiApp --server http://host:port [--nick Name] [--refresh 2]
                    """);
            System.exit(0);
        }
    }

    private static final class ChatGuiFrame {
        private static final DateTimeFormatter FORMATTER =
                DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

        private final GuiConfig config;
        private final HttpClient client;
        private final URI historyUri;
        private final URI chatUri;
        private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
        private final JTextArea logArea = new JTextArea();
        private final JTextField inputField = new JTextField();
        private final JTextField nickField = new JTextField();
        private final JButton sendButton = new JButton("Send");
        private final JButton refreshButton = new JButton("Refresh");
        private final JLabel statusLabel = new JLabel(" ");
        private final JLabel serverLabel;
        private final CopyOnWriteArrayList<ChatEntry> currentEntries = new CopyOnWriteArrayList<>();
        private volatile boolean closed;

        ChatGuiFrame(GuiConfig config) {
            this.config = config;
            this.client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            this.historyUri = config.server.resolve("/history");
            this.chatUri = config.server.resolve("/chat");
            this.serverLabel = new JLabel("Server: " + config.server);
        }

        void showWindow() {
            JFrame frame = new JFrame("Distributed Chat GUI");
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.setLayout(new BorderLayout(8, 8));
            frame.add(buildTopPanel(), BorderLayout.NORTH);
            frame.add(buildCenterPanel(), BorderLayout.CENTER);
            frame.add(buildBottomPanel(), BorderLayout.SOUTH);
            frame.setSize(640, 480);
            frame.setLocationRelativeTo(null);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    shutdown();
                }
            });
            frame.setVisible(true);
            startPolling();
        }

        private JPanel buildTopPanel() {
            JPanel panel = new JPanel(new BorderLayout(8, 8));
            panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
            panel.add(serverLabel, BorderLayout.CENTER);
            refreshButton.addActionListener(event -> triggerRefresh());
            panel.add(refreshButton, BorderLayout.EAST);
            return panel;
        }

        private JPanel buildCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            logArea.setEditable(false);
            logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            logArea.setLineWrap(true);
            logArea.setWrapStyleWord(true);
            JScrollPane scrollPane = new JScrollPane(logArea);
            panel.add(scrollPane, BorderLayout.CENTER);
            return panel;
        }

        private JPanel buildBottomPanel() {
            JPanel root = new JPanel(new BorderLayout(8, 8));
            root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

            JPanel inputRow = new JPanel(new BorderLayout(8, 8));
            JPanel nickPanel = new JPanel(new BorderLayout(4, 4));
            nickPanel.add(new JLabel("Nick:"), BorderLayout.WEST);
            nickField.setText(config.nickname);
            nickPanel.add(nickField, BorderLayout.CENTER);
            inputRow.add(nickPanel, BorderLayout.WEST);
            inputRow.add(inputField, BorderLayout.CENTER);
            sendButton.addActionListener(event -> sendMessage());
            inputField.addActionListener(event -> sendMessage());
            inputRow.add(sendButton, BorderLayout.EAST);
            root.add(inputRow, BorderLayout.CENTER);

            statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));
            root.add(statusLabel, BorderLayout.SOUTH);
            return root;
        }

        private void startPolling() {
            executor.scheduleAtFixedRate(() -> {
                if (closed) {
                    return;
                }
                refreshHistory();
            }, 0, config.refreshSeconds, TimeUnit.SECONDS);
        }

        private void triggerRefresh() {
            executor.execute(this::refreshHistory);
        }

        private void refreshHistory() {
            try {
                HttpRequest request = HttpRequest.newBuilder(historyUri)
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    showStatus("History failed: HTTP " + response.statusCode());
                    return;
                }
                List<ChatEntry> entries = HistoryParser.parse(response.body());
                updateEntries(entries);
                showStatus("Updated " + entries.size() + " messages.");
            } catch (IOException | InterruptedException ex) {
                showStatus("Refresh error: " + ex.getMessage());
                Thread.currentThread().interrupt();
            }
        }

        private void updateEntries(List<ChatEntry> entries) {
            if (entries.equals(currentEntries)) {
                return;
            }
            currentEntries.clear();
            currentEntries.addAll(entries);
            StringBuilder builder = new StringBuilder();
            for (ChatEntry entry : entries) {
                builder.append('[')
                        .append(FORMATTER.format(Instant.ofEpochMilli(entry.timestamp())))
                        .append("] ");
                String nick = (entry.nick() == null || entry.nick().isBlank()) ? "?" : entry.nick();
                builder.append(nick).append(": ").append(entry.text()).append(System.lineSeparator());
            }
            String text = builder.toString();
            SwingUtilities.invokeLater(() -> {
                logArea.setText(text);
                logArea.setCaretPosition(logArea.getDocument().getLength());
            });
        }

        private void sendMessage() {
            String text = inputField.getText().trim();
            if (text.isEmpty()) {
                return;
            }
            String nick = nickField.getText().trim();
            setInputEnabled(false);
            executor.execute(() -> {
                try {
                    HttpRequest request = HttpRequest.newBuilder(chatUri)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(encodeBody(text, nick), StandardCharsets.UTF_8))
                            .timeout(Duration.ofSeconds(5))
                            .build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (response.statusCode() != 200) {
                        showStatus("Send failed: HTTP " + response.statusCode());
                        return;
                    }
                    SwingUtilities.invokeLater(() -> {
                        inputField.setText("");
                        showStatus("Sent message.");
                    });
                    triggerRefresh();
                } catch (IOException | InterruptedException ex) {
                    showStatus("Send error: " + ex.getMessage());
                    Thread.currentThread().interrupt();
                } finally {
                    setInputEnabled(true);
                }
            });
        }

        private static String encodeBody(String text, String nick) {
            StringBuilder body = new StringBuilder();
            body.append("text=").append(URLEncoder.encode(text, StandardCharsets.UTF_8));
            if (nick != null && !nick.isBlank()) {
                body.append("&nick=").append(URLEncoder.encode(nick, StandardCharsets.UTF_8));
            }
            return body.toString();
        }

        private void setInputEnabled(boolean enabled) {
            SwingUtilities.invokeLater(() -> {
                inputField.setEnabled(enabled);
                sendButton.setEnabled(enabled);
                if (enabled) {
                    inputField.requestFocusInWindow();
                }
            });
        }

        private void showStatus(String message) {
            SwingUtilities.invokeLater(() -> statusLabel.setText(message));
        }

        private void shutdown() {
            closed = true;
            executor.shutdownNow();
        }
    }

    private record ChatEntry(String id, String nick, String text, long timestamp) {
    }

    private static final class HistoryParser {
        private final String data;
        private int index;

        private HistoryParser(String data) {
            this.data = data == null ? "" : data.trim();
        }

        static List<ChatEntry> parse(String json) throws IOException {
            return new HistoryParser(json).parseArray();
        }

        private List<ChatEntry> parseArray() throws IOException {
            List<ChatEntry> entries = new ArrayList<>();
            if (data.isEmpty()) {
                return entries;
            }
            expect('[');
            skipWhitespace();
            if (peek() == ']') {
                index++;
                return entries;
            }
            boolean done = false;
            while (!done) {
                entries.add(parseObject());
                skipWhitespace();
                char ch = peek();
                if (ch == ',') {
                    index++;
                } else if (ch == ']') {
                    done = true;
                    index++;
                } else {
                    throw new IOException("Unexpected character '" + ch + "' in history payload");
                }
            }
            return entries;
        }

        private ChatEntry parseObject() throws IOException {
            expect('{');
            String id = null;
            String nick = null;
            String text = null;
            long timestamp = 0L;
            boolean done = false;
            while (!done) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                switch (key) {
                    case "id" -> id = parseStringOrNull();
                    case "nick" -> nick = parseStringOrNull();
                    case "text" -> text = parseStringOrNull();
                    case "timestamp" -> timestamp = parseNumber();
                    default -> throw new IOException("Unexpected field " + key);
                }
                skipWhitespace();
                char ch = peek();
                if (ch == ',') {
                    index++;
                } else if (ch == '}') {
                    done = true;
                    index++;
                } else {
                    throw new IOException("Bad object separator near index " + index);
                }
            }
            return new ChatEntry(id, nick, text == null ? "" : text, timestamp);
        }

        private long parseNumber() throws IOException {
            int start = index;
            while (index < data.length()) {
                char ch = data.charAt(index);
                if (!Character.isDigit(ch)) {
                    break;
                }
                index++;
            }
            if (start == index) {
                throw new IOException("Expected number at index " + index);
            }
            return Long.parseLong(data, start, index, 10);
        }

        private String parseStringOrNull() throws IOException {
            if (peek() == 'n') {
                expectSequence("null");
                return null;
            }
            return parseString();
        }

        private void expectSequence(String literal) throws IOException {
            for (int i = 0; i < literal.length(); i++) {
                char ch = literal.charAt(i);
                if (peek() != ch) {
                    throw new IOException("Expected '" + literal + "'");
                }
                index++;
            }
        }

        private String parseString() throws IOException {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (index < data.length()) {
                char ch = data.charAt(index++);
                if (ch == '"') {
                    break;
                }
                if (ch == '\\') {
                    if (index >= data.length()) {
                        throw new IOException("Unterminated escape sequence");
                    }
                    char esc = data.charAt(index++);
                    builder.append(switch (esc) {
                        case '"', '\\', '/' -> esc;
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case 'u' -> decodeUnicode();
                        default -> esc;
                    });
                } else {
                    builder.append(ch);
                }
            }
            return builder.toString();
        }

        private char decodeUnicode() throws IOException {
            if (index + 4 > data.length()) {
                throw new IOException("Bad unicode escape");
            }
            String hex = data.substring(index, index + 4);
            index += 4;
            return (char) Integer.parseInt(hex, 16);
        }

        private void expect(char expected) throws IOException {
            skipWhitespace();
            char ch = peek();
            if (ch != expected) {
                throw new IOException("Expected '" + expected + "' but saw '" + ch + "'");
            }
            index++;
        }

        private void skipWhitespace() {
            while (index < data.length() && Character.isWhitespace(data.charAt(index))) {
                index++;
            }
        }

        private char peek() throws IOException {
            if (index >= data.length()) {
                throw new IOException("Unexpected end of payload");
            }
            return data.charAt(index);
        }
    }
}
