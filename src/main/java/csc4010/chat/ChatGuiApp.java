
package csc4010.chat;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    public static void launch(URI server, String nickname, int refreshSeconds) {
        GuiConfig config = new GuiConfig(server, nickname, Math.max(1, refreshSeconds));
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
        private static final Color BACKGROUND = new Color(6, 6, 6);
        private static final Color PANEL = new Color(12, 12, 12);
        private static final Color ACCENT = new Color(199, 164, 90);
        private static final Color TEXT = new Color(238, 238, 238);
        private static final Color MUTED = new Color(180, 180, 180);
        private static final Font HEADER_FONT = new Font("Georgia", Font.BOLD, 16);
        private static final Font BODY_FONT = new Font("Segoe UI", Font.PLAIN, 13);
        private static final Path DEFAULT_LOGO = Path.of("src/main/java/csc4010/chat/unnamed.jpg");
        private static final int LOGO_HEIGHT = 90;
        private static final int LOGO_WIDTH = 120;

        private final GuiConfig config;
        private final HttpClient client;
        private final URI historyUri;
        private final URI chatUri;
        private final URI controlUri;
        private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
        private final JTextArea logArea = new JTextArea();
        private final JTextField inputField = new JTextField();
        private final JTextField nickField = new JTextField();
        private final JTextField roomField = new JTextField(10);
        private final JTextField resendField = new JTextField(10);
        private final JTextField forgetField = new JTextField(10);
        private final JSpinner dropInSpinner = new JSpinner(new SpinnerNumberModel(0.0d, 0.0d, 1.0d, 0.05d));
        private final JSpinner dropOutSpinner = new JSpinner(new SpinnerNumberModel(0.0d, 0.0d, 1.0d, 0.05d));
        private final JSpinner robotSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 120, 1));
        private final JButton sendButton = new JButton("Send");
        private final JLabel statusLabel = new JLabel(" ");
        private final JLabel serverLabel;
        private final JLabel logoLabel;
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
            this.controlUri = config.server.resolve("/control");
            this.serverLabel = new JLabel("Server: " + config.server);
            this.serverLabel.setFont(HEADER_FONT);
            this.serverLabel.setForeground(TEXT);
            this.logoLabel = loadLogo(DEFAULT_LOGO, LOGO_HEIGHT);
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
            frame.getContentPane().setBackground(BACKGROUND);
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
            panel.setBackground(PANEL);
            if (logoLabel != null) {
                JPanel logoWrapper = new JPanel();
                logoWrapper.setBackground(PANEL);
                logoWrapper.add(logoLabel);
                panel.add(logoWrapper, BorderLayout.WEST);
            }
            panel.add(serverLabel, BorderLayout.CENTER);

            JPanel controls = buildControlsBar();
            panel.add(controls, BorderLayout.SOUTH);
            return panel;
        }

        private JPanel buildCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            panel.setBackground(BACKGROUND);
            logArea.setEditable(false);
            logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            logArea.setBackground(PANEL);
            logArea.setForeground(TEXT);
            logArea.setCaretColor(ACCENT);
            logArea.setLineWrap(true);
            logArea.setWrapStyleWord(true);
            JScrollPane scrollPane = new JScrollPane(logArea);
            scrollPane.getViewport().setBackground(PANEL);
            scrollPane.setBorder(BorderFactory.createLineBorder(ACCENT, 1));
            panel.add(scrollPane, BorderLayout.CENTER);
            return panel;
        }

        private JPanel buildBottomPanel() {
            JPanel root = new JPanel(new BorderLayout(8, 8));
            root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            root.setBackground(PANEL);

            JPanel inputRow = new JPanel(new BorderLayout(8, 8));
            inputRow.setBackground(PANEL);
            JPanel nickPanel = new JPanel(new BorderLayout(4, 4));
            nickPanel.setBackground(PANEL);
            JLabel nickLabel = new JLabel("Nick:");
            nickLabel.setForeground(TEXT);
            nickPanel.add(nickLabel, BorderLayout.WEST);
            nickField.setText(config.nickname);
            styleField(nickField);
            nickPanel.add(nickField, BorderLayout.CENTER);
            inputRow.add(nickPanel, BorderLayout.WEST);
            styleField(inputField);
            inputRow.add(inputField, BorderLayout.CENTER);
            styleButton(sendButton);
            sendButton.addActionListener(event -> sendMessage());
            inputField.addActionListener(event -> sendMessage());
            JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            rightButtons.setBackground(PANEL);
            JButton nickUpdateButton = createButton("Set Nick", () -> executeControl("nick", Map.of("value", nickField.getText().trim())));
            rightButtons.add(nickUpdateButton);
            rightButtons.add(sendButton);
            inputRow.add(rightButtons, BorderLayout.EAST);
            root.add(inputRow, BorderLayout.CENTER);

            statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));
            statusLabel.setForeground(MUTED);
            root.add(statusLabel, BorderLayout.SOUTH);
            return root;
        }

        private JPanel buildControlsBar() {
            JPanel wrapper = new JPanel(new BorderLayout());
            wrapper.setBackground(PANEL);
            wrapper.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

            JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
            row1.setBackground(PANEL);
            styleField(roomField);
            roomField.setColumns(10);
            JButton roomButton = createButton("Room", () -> executeControl("room", Map.of("name", roomField.getText().trim())));
            JButton clearButton = createButton("Clear", () -> executeControl("clear", Map.of()));
            JButton syncButton = createButton("Sync", () -> executeControl("sync", Map.of()));
            row1.add(labelled("Room:", roomField));
            row1.add(roomButton);
            row1.add(clearButton);
            row1.add(syncButton);

            JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
            row2.setBackground(PANEL);
            styleSpinner(dropInSpinner);
            styleSpinner(dropOutSpinner);
            JButton dropButton = createButton("Apply Drops", this::applyDrops);
            styleSpinner(robotSpinner);
            JButton robotStart = createButton("Robot Start", () -> executeControl("robot_start", Map.of("seconds", robotSpinner.getValue().toString())));
            JButton robotStop = createButton("Robot Stop", () -> executeControl("robot_stop", Map.of()));
            JButton resendButton = createButton("Resend", () -> executeControl("resend", Map.of("ids", resendField.getText().trim())));
            JButton forgetButton = createButton("Forget", () -> executeControl("forget", Map.of("id", forgetField.getText().trim())));
            styleField(resendField);
            styleField(forgetField);
            resendField.setColumns(10);
            forgetField.setColumns(10);
            JButton fileButton = createButton("Send File", this::sendFile);
            JButton quitButton = createButton("Quit", () -> executeControl("quit", Map.of()));

            row2.add(labelled("Drop In/Out:", dropInSpinner, dropOutSpinner));
            row2.add(dropButton);
            row2.add(labelled("Robot (s):", robotSpinner));
            row2.add(robotStart);
            row2.add(robotStop);
            row2.add(labelled("Resend:", resendField));
            row2.add(resendButton);
            row2.add(labelled("Forget:", forgetField));
            row2.add(forgetButton);
            row2.add(fileButton);
            row2.add(quitButton);

            JPanel rows = new JPanel(new BorderLayout());
            rows.setBackground(PANEL);
            rows.add(row1, BorderLayout.NORTH);
            rows.add(row2, BorderLayout.SOUTH);
            wrapper.add(rows, BorderLayout.CENTER);
            return wrapper;
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

        private JLabel loadLogo(Path path, int targetHeight) {
            if (path == null || !Files.exists(path)) {
                return null;
            }
            try (InputStream in = Files.newInputStream(path)) {
                var img = ImageIO.read(in);
                if (img == null) {
                    return null;
                }
                int width = img.getWidth();
                int height = img.getHeight();
                double scale = Math.min(1.0, Math.min((double) targetHeight / height, (double) LOGO_WIDTH / width));
                int targetW = (int) Math.round(width * scale);
                int targetH = (int) Math.round(height * scale);
                java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(
                        targetW,
                        targetH,
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g2 = scaled.createGraphics();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.drawImage(img, 0, 0, targetW, targetH, null);
                g2.dispose();
                JLabel label = new JLabel(new javax.swing.ImageIcon(scaled));
                label.setPreferredSize(new Dimension(targetW, targetH));
                return label;
            } catch (IOException ignored) {
                return null;
            }
        }

        private void styleButton(JButton button) {
            button.setBackground(ACCENT);
            button.setForeground(Color.BLACK);
            button.setFocusPainted(false);
            button.setFont(BODY_FONT.deriveFont(Font.BOLD, 12f));
            button.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        }

        private void styleField(JTextField field) {
            field.setBackground(BACKGROUND);
            field.setForeground(TEXT);
            field.setCaretColor(ACCENT);
            field.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(ACCENT, 1),
                    BorderFactory.createEmptyBorder(4, 6, 4, 6)
            ));
            field.setFont(BODY_FONT);
        }

        private void styleSpinner(JSpinner spinner) {
            spinner.setBackground(BACKGROUND);
            spinner.setForeground(TEXT);
            spinner.setFont(BODY_FONT);
            ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setBackground(BACKGROUND);
            ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setForeground(TEXT);
            ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setCaretColor(ACCENT);
            spinner.setBorder(BorderFactory.createLineBorder(ACCENT, 1));
        }

        private JButton createButton(String text, Runnable action) {
            JButton button = new JButton(text);
            styleButton(button);
            button.addActionListener(e -> action.run());
            return button;
        }

        private JPanel labelled(String label, java.awt.Component... components) {
            JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            panel.setBackground(PANEL);
            JLabel lbl = new JLabel(label);
            lbl.setForeground(TEXT);
            lbl.setFont(BODY_FONT.deriveFont(Font.BOLD));
            panel.add(lbl);
            for (java.awt.Component c : components) {
                panel.add(c);
            }
            return panel;
        }

        private void applyDrops() {
            double in = ((Number) dropInSpinner.getValue()).doubleValue();
            double out = ((Number) dropOutSpinner.getValue()).doubleValue();
            executeControl("drop_in", Map.of("rate", formatRate(in)));
            executeControl("drop_out", Map.of("rate", formatRate(out)));
        }

        private String formatRate(double rate) {
            double clamped = Math.max(0.0d, Math.min(1.0d, rate));
            return String.format(Locale.ROOT, "%.2f", clamped);
        }

        private void sendFile() {
            JFileChooser chooser = new JFileChooser();
            int result = chooser.showOpenDialog(null);
            if (result == JFileChooser.APPROVE_OPTION) {
                String path = chooser.getSelectedFile().getAbsolutePath();
                executeControl("sendfile", Map.of("path", path));
            }
        }

        private void executeControl(String action, Map<String, String> params) {
            executor.execute(() -> {
                try {
                    ControlResult result = postControl(action, params);
                    showStatus(result.message());
                } catch (IOException | InterruptedException ex) {
                    showStatus("Control error: " + ex.getMessage());
                    Thread.currentThread().interrupt();
                }
            });
        }

        private ControlResult postControl(String action, Map<String, String> params) throws IOException, InterruptedException {
            LinkedHashMap<String, String> body = new LinkedHashMap<>();
            body.put("action", action);
            if (params != null) {
                params.forEach((k, v) -> {
                    if (v != null) {
                        body.put(k, v);
                    }
                });
            }
            HttpRequest request = HttpRequest.newBuilder(controlUri)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(encodeForm(body), StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return parseControlResult(response.body(), response.statusCode());
        }

        private ControlResult parseControlResult(String body, int status) {
            boolean ok = status == 200 && body.contains("\"ok\":true");
            String message = body;
            int idx = body.indexOf("\"message\":");
            if (idx >= 0) {
                int start = body.indexOf('"', idx + 10);
                int end = body.indexOf('"', start + 1);
                if (start >= 0 && end > start) {
                    message = body.substring(start + 1, end);
                }
            }
            return new ControlResult(ok, message);
        }

        private String encodeForm(Map<String, String> params) {
            StringBuilder builder = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                if (!first) {
                    builder.append('&');
                }
                builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
                first = false;
            }
            return builder.toString();
        }
    }

    private record ChatEntry(String id, String nick, String text, long timestamp) {
    }

    private record ControlResult(boolean ok, String message) {
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
