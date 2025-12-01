package csc4010.chat;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Parses and stores immutable configuration derived from CLI arguments.
 */
public final class NodeConfig {
    private final UUID nodeId;
    private final String nickname;
    private final int listenPort;
    private final List<InetSocketAddress> seedPeers;
    private final double dropInRate;
    private final double dropOutRate;
    private final Optional<Path> transcriptPath;
    private final Optional<Integer> robotIntervalSeconds;
    private final boolean headless;
    private final Optional<Integer> lifetimeSeconds;
    private final boolean discoveryEnabled;
    private final int discoveryPort;
    private final Optional<Integer> httpPort;
    private final Path downloadDirectory;
    private final String room;

    private NodeConfig(
            UUID nodeId,
            String nickname,
            int listenPort,
            List<InetSocketAddress> seedPeers,
            double dropInRate,
            double dropOutRate,
            Optional<Path> transcriptPath,
            Optional<Integer> robotIntervalSeconds,
            boolean headless,
            Optional<Integer> lifetimeSeconds,
            boolean discoveryEnabled,
            int discoveryPort,
            Optional<Integer> httpPort,
            Path downloadDirectory,
            String room) {
        this.nodeId = nodeId;
        this.nickname = nickname;
        this.listenPort = listenPort;
        this.seedPeers = List.copyOf(seedPeers);
        this.dropInRate = dropInRate;
        this.dropOutRate = dropOutRate;
        this.transcriptPath = transcriptPath;
        this.robotIntervalSeconds = robotIntervalSeconds;
        this.headless = headless;
        this.lifetimeSeconds = lifetimeSeconds;
        this.discoveryEnabled = discoveryEnabled;
        this.discoveryPort = discoveryPort;
        this.httpPort = httpPort;
        this.downloadDirectory = downloadDirectory;
        this.room = room;
    }

    public UUID nodeId() {
        return nodeId;
    }

    public String nickname() {
        return nickname;
    }

    public int listenPort() {
        return listenPort;
    }

    public List<InetSocketAddress> seedPeers() {
        return seedPeers;
    }

    public double dropInRate() {
        return dropInRate;
    }

    public double dropOutRate() {
        return dropOutRate;
    }

    public Optional<Path> transcriptPath() {
        return transcriptPath;
    }

    public Optional<Integer> robotIntervalSeconds() {
        return robotIntervalSeconds;
    }

    public boolean headless() {
        return headless;
    }

    public Optional<Integer> lifetimeSeconds() {
        return lifetimeSeconds;
    }

    public boolean discoveryEnabled() {
        return discoveryEnabled;
    }

    public int discoveryPort() {
        return discoveryPort;
    }

    public Optional<Integer> httpPort() {
        return httpPort;
    }

    public Path downloadDirectory() {
        return downloadDirectory;
    }

    public String room() {
        return room;
    }

    public static NodeConfig fromArgs(String[] args) {
        String nickname = null;
        Integer port = null;
        final List<InetSocketAddress> peers = new ArrayList<>();
        Double dropIn = 0.0d;
        Double dropOut = 0.0d;
        Path transcript = null;
        Integer robotSeconds = null;
        Integer lifetime = null;
        boolean headless = System.console() == null;
        boolean discoveryEnabled = true;
        int discoveryPort = 57500;
        Integer httpPort = null;
        Path downloads = Path.of("downloads");
        String room = "lobby";

        for (int idx = 0; idx < args.length; idx++) {
            String raw = args[idx];
            if (!raw.startsWith("--") && !"-h".equals(raw)) {
                continue;
            }
            String key;
            String value = null;
            int equals = raw.indexOf('=');
            if (equals > 0) {
                key = raw.substring(0, equals);
                value = raw.substring(equals + 1);
            } else {
                key = raw;
            }
            switch (key) {
                case "--headless" -> headless = true;
                case "--console" -> headless = false;
                case "--no-discovery" -> discoveryEnabled = false;
                case "--help", "-h" -> {
                    printUsage();
                    System.exit(0);
                }
                default -> {
                    if (value == null) {
                        if (idx + 1 >= args.length) {
                            throw new IllegalArgumentException("Missing value for flag " + key);
                        }
                        value = args[++idx];
                    }
                    switch (key) {
                        case "--nick" -> nickname = value;
                        case "--port" -> port = parsePort(value);
                        case "--peer" -> peers.add(parseAddress(value));
                        case "--drop-in" -> dropIn = clampRate(value);
                        case "--drop-out" -> dropOut = clampRate(value);
                        case "--log" -> transcript = Path.of(value);
                        case "--robot" -> robotSeconds = Math.max(1, Integer.parseInt(value));
                        case "--lifetime" -> lifetime = Math.max(1, Integer.parseInt(value));
                        case "--discover-port" -> discoveryPort = Integer.parseInt(value);
                        case "--http-port" -> httpPort = Integer.parseInt(value);
                        case "--downloads" -> downloads = Path.of(value);
                        case "--room" -> room = value.isBlank() ? "lobby" : value;
                        default -> throw new IllegalArgumentException("Unknown flag: " + key);
                    }
                }
            }
        }

        if (port == null) {
            port = 5000;
        }

        UUID nodeId = UUID.randomUUID();
        if (nickname == null || nickname.isBlank()) {
            String suffix = nodeId.toString().substring(0, 4).toUpperCase(Locale.ROOT);
            nickname = "Peer-" + suffix;
        }

        return new NodeConfig(
                nodeId,
                nickname,
                port,
                peers,
                dropIn,
                dropOut,
                Optional.ofNullable(transcript),
                Optional.ofNullable(robotSeconds),
                headless,
                Optional.ofNullable(lifetime),
                discoveryEnabled,
                discoveryPort,
                Optional.ofNullable(httpPort),
                downloads.toAbsolutePath(),
                room);
    }

    private static double clampRate(String value) {
        double rate = Double.parseDouble(value);
        if (rate < 0.0d || rate > 1.0d) {
            throw new IllegalArgumentException("Drop probabilities must be in [0,1]");
        }
        return rate;
    }

    private static int parsePort(String value) {
        if ("auto".equalsIgnoreCase(value)) {
            return 0;
        }
        int parsed = Integer.parseInt(value);
        if (parsed < 0 || parsed > 65535) {
            throw new IllegalArgumentException("Port must be between 0 and 65535");
        }
        return parsed;
    }

    private static InetSocketAddress parseAddress(String spec) {
        String[] parts = spec.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Peer must be host:port but was " + spec);
        }
        return new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
    }

    private static void printUsage() {
        System.out.println("""
                Distributed UDP chat node
                Usage: java csc4010.chat.ChatNode --port <port> [--nick <name>] [--peer host:port ...]
                       [--drop-in 0.1] [--drop-out 0.1] [--robot 5] [--log path]
                       [--lifetime 10] [--headless] [--http-port 8080]
                       [--discover-port 57500] [--no-discovery] [--downloads dir]
                       [--room lobby]
                """);
    }
}
