package csc4010.chat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Main peer process for the distributed chat overlay.
 */
public final class ChatNode {
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(2);
    private static final Duration PEER_TTL = Duration.ofSeconds(10);
    private static final List<String> ROBOT_LINES = List.of(
            "Distributed dreams never sleep.",
            "Who dropped a packet? Own up!",
            "Lamport clocks make the world go round.",
            "Simulating failure so you do not have to.",
            "Hello peers, is anyone there?",
            "Ordering all things, even the coffee queue.",
            "UDP but make it reliable-ish.",
            "Requesting another history slice."
    );

    private final NodeConfig config;
    private final LamportClock clock = new LamportClock();
    private final MessageStore history = new MessageStore();
    private final PeerRegistry peers = new PeerRegistry();
    private final DropSimulator dropSimulator;
    private final DatagramService datagram;
    private final int listenPort;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> nickname;
    private final AtomicReference<String> room;
    private final AtomicReference<BufferedWriter> transcriptWriter = new AtomicReference<>();
    private final MessageRouter router = new MessageRouter();
    private final HistorySyncService historySync = new HistorySyncService();
    private final RobotSpeaker robotSpeaker = new RobotSpeaker();
    private final FileTransferManager fileTransferManager;
    private final DiscoveryService discoveryService;
    private final ExternalHttpServer httpServer;
    private final boolean headless;
    private ScheduledFuture<?> discoveryTask;

    private ChatNode(NodeConfig config) throws Exception {
        this.config = config;
        this.nickname = new AtomicReference<>(config.nickname());
        this.room = new AtomicReference<>(config.room());
        this.dropSimulator = new DropSimulator(config.dropInRate(), config.dropOutRate());
        DatagramBinding binding = initDatagramService(config.listenPort());
        this.datagram = binding.service();
        this.listenPort = binding.port();
        config.transcriptPath().ifPresent(this::initTranscriptWriter);
        this.headless = config.headless();
        this.fileTransferManager = new FileTransferManager(config.downloadDirectory(), this::logSystem);
        if (config.discoveryEnabled()) {
            this.discoveryService = initDiscoveryService(config, listenPort);
        } else {
            this.discoveryService = null;
        }
        if (config.httpPort().isPresent()) {
            this.httpServer = new ExternalHttpServer(
                    config.httpPort().get(),
                    this::handleExternalMessage,
                    () -> history.snapshot(currentRoom()),
                    this::healthSnapshot);
        } else {
            this.httpServer = null;
        }
    }

    private DatagramBinding initDatagramService(int requestedPort) throws Exception {
        BindException last = null;
        int attemptPort = requestedPort;
        for (int attempts = 0; attempts < 10; attempts++) {
            try {
                DatagramService service = new DatagramService(attemptPort, router::handle, dropSimulator);
                return new DatagramBinding(service, service.localPort());
            } catch (BindException bind) {
                last = bind;
                if (requestedPort == 0) {
                    throw bind;
                }
                attemptPort++;
            }
        }
        throw last != null ? last : new BindException("Unable to bind UDP socket");
    }

    private String currentRoom() {
        return room.get();
    }

    private DiscoveryService initDiscoveryService(NodeConfig config, int listenPort) throws Exception {
        try {
            return new DiscoveryService(
                    config.discoveryPort(),
                    listenPort,
                    config.nodeId().toString(),
                    nickname::get,
                    this::handleDiscoveredPeer);
        } catch (BindException bind) {
            System.err.printf(
                    "Discovery disabled: failed to bind UDP %d (%s). " +
                            "Free the port or pass --discover-port <port> / --no-discovery.%n",
                    config.discoveryPort(),
                    bind.getMessage());
            return null;
        }
    }

    private record DatagramBinding(DatagramService service, int port) {}

    public static void main(String[] args) throws Exception {
        NodeConfig config = NodeConfig.fromArgs(args);
        ChatNode node = new ChatNode(config);
        node.start();
    }

    private void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
        datagram.start();
        config.seedPeers().forEach(peers::addSeed);
        if (discoveryService != null) {
            discoveryService.start();
            if (config.seedPeers().isEmpty()) {
                discoveryTask = scheduler.scheduleAtFixedRate(discoveryService::broadcastProbe, 0, 5, TimeUnit.SECONDS);
            } else {
                discoveryService.broadcastProbe();
            }
        }
        if (httpServer != null) {
            httpServer.start();
            System.out.println("HTTP API listening on http://localhost:" + httpServer.port());
        }
        sendInitialHelloes();
        scheduler.scheduleAtFixedRate(this::sendHeartbeats, 1, HEARTBEAT_INTERVAL.toSeconds(), TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::purgeStalePeers, 5, 5, TimeUnit.SECONDS);
        if (!headless) {
            scheduler.execute(() -> new ConsoleShell(this::handleConsoleLine).run());
        } else {
            System.out.println("Console disabled (headless mode).");
        }
        config.robotIntervalSeconds().ifPresent(robotSpeaker::start);
        config.lifetimeSeconds().ifPresent(seconds ->
                scheduler.schedule(() -> {
                    System.out.println("Auto-shutdown after " + seconds + "s.");
                    shutdown();
                }, seconds, TimeUnit.SECONDS));
        System.out.printf("Chat node %s listening on UDP %d as %s in room %s%n",
                config.nodeId(), listenPort, nickname.get(), currentRoom());
        System.out.println("Type /help for commands.");
        while (running.get()) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Packet goodbye = identityPacket(MessageType.LEAVE).build();
        broadcast(goodbye);
        robotSpeaker.shutdown();
        if (discoveryTask != null) {
            discoveryTask.cancel(true);
        }
        if (discoveryService != null) {
            discoveryService.close();
        }
        if (httpServer != null) {
            httpServer.close();
        }
        scheduler.shutdownNow();
        datagram.close();
        Optional.ofNullable(transcriptWriter.get()).ifPresent(writer -> {
            try {
                writer.flush();
                writer.close();
            } catch (IOException ignored) {
            }
        });
        System.out.println("Node stopped.");
    }

    private void initTranscriptWriter(Path path) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            BufferedWriter writer = Files.newBufferedWriter(
                    path,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            transcriptWriter.set(writer);
        } catch (IOException ioe) {
            System.err.println("Could not open transcript file: " + ioe.getMessage());
        }
    }

    private void sendInitialHelloes() {
        if (config.seedPeers().isEmpty()) {
            return;
        }
        Packet hello = identityPacket(MessageType.HELLO)
                .put("clock", clock.current())
                .put("askHistory", history.size(currentRoom()) == 0 ? "1" : "0")
                .build();
        datagram.broadcast(config.seedPeers(), hello);
    }

    private void sendHeartbeats() {
        Packet heartbeat = Packet.builder(MessageType.HEARTBEAT)
                .put("nodeId", config.nodeId().toString())
                .put("clock", clock.current())
                .put("room", currentRoom())
                .build();
        datagram.broadcast(peers.peerAddresses(currentRoom(), null), heartbeat);
    }

    private void purgeStalePeers() {
        peers.evictStale(PEER_TTL);
    }

    private void handleConsoleLine(String raw) {
        if (raw == null) {
            shutdown();
            return;
        }
        String line = raw.trim();
        if (line.isEmpty()) {
            return;
        }
        if (!line.startsWith("/")) {
            publishLocalMessage(line, false);
            return;
        }
        String[] parts = line.substring(1).split("\\s+");
        if (parts.length == 0) {
            return;
        }
        switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "help" -> printHelp();
            case "peers" -> peers.allPeers().forEach(info -> System.out.println(" - " + info.describe()));
            case "history" -> history.snapshot(currentRoom()).forEach(msg -> System.out.println(msg.toDisplayString()));
            case "sync" -> historySync.requestFromAnyPeer();
            case "clear" -> {
                history.clear(currentRoom());
                System.out.println("Local log cleared for room " + currentRoom() + ".");
                historySync.requestFromAnyPeer();
            }
            case "robot" -> handleRobotCommand(parts);
            case "drop" -> handleDropCommand(parts);
            case "nick" -> handleNickCommand(parts);
            case "room" -> handleRoomCommand(parts);
            case "sendfile" -> handleSendFile(parts);
            case "discover" -> triggerDiscovery();
            case "resend" -> {
                if (parts.length < 2) {
                    System.out.println("Usage: /resend <messageId>");
                } else {
                    historySync.requestSpecific(parts[1]);
                }
            }
            case "forget" -> {
                if (parts.length < 2) {
                    System.out.println("Usage: /forget <messageId>");
                } else if (history.remove(currentRoom(), parts[1])) {
                    System.out.println("Locally removed " + parts[1]);
                } else {
                    System.out.println("Message not known.");
                }
            }
            case "quit" -> shutdown();
            default -> System.out.println("Unknown command. Type /help.");
        }
    }

    private void printHelp() {
        System.out.println("""
                Commands:
                  /help                Show this help
                  /peers               List known peers
                  /history             Print cached chat log
                  /room <name>         Switch active room
                  /sync                Request full history from peers
                  /clear               Clear local history and sync again
                  /robot start <sec>   Start automated chatter
                  /robot stop          Stop robot chatter
                  /drop in|out <rate>  Adjust simulated packet loss
                  /nick <name>         Change nickname
                  /sendfile <path>     Transfer a binary file to peers
                  /discover            Broadcast a discovery probe
                  /resend <id>         Ask peers for a message id
                  /forget <id>         Delete a message locally
                  /quit                Exit gracefully
                """);
    }

    private void handleRobotCommand(String[] parts) {
        if (parts.length < 2) {
            System.out.println("Usage: /robot start <seconds> | /robot stop");
            return;
        }
        switch (parts[1].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                if (parts.length < 3) {
                    System.out.println("Provide the interval in seconds.");
                } else {
                    int seconds = Math.max(1, Integer.parseInt(parts[2]));
                    robotSpeaker.start(seconds);
                }
            }
            case "stop" -> robotSpeaker.stop();
            default -> System.out.println("Usage: /robot start <seconds> | /robot stop");
        }
    }

    private void handleDropCommand(String[] parts) {
        if (parts.length < 3) {
            System.out.println("Usage: /drop in|out <0-1>");
            return;
        }
        double rate = Double.parseDouble(parts[2]);
        if ("in".equalsIgnoreCase(parts[1])) {
            dropSimulator.setInbound(rate);
        } else if ("out".equalsIgnoreCase(parts[1])) {
            dropSimulator.setOutbound(rate);
        } else {
            System.out.println("Usage: /drop in|out <0-1>");
            return;
        }
        System.out.printf("Drops -> inbound=%.2f outbound=%.2f%n", dropSimulator.inboundRate(), dropSimulator.outboundRate());
    }

    private void handleNickCommand(String[] parts) {
        if (parts.length < 2) {
            System.out.println("Usage: /nick <name>");
            return;
        }
        nickname.set(parts[1]);
        System.out.println("Nickname updated.");
        Packet announce = identityPacket(MessageType.HELLO)
                .put("clock", clock.tick())
                .put("askHistory", "0")
                .build();
        broadcast(announce);
    }

    private void handleRoomCommand(String[] parts) {
        if (parts.length < 2) {
            System.out.println("Usage: /room <name>");
            return;
        }
        String targetRoom = parts[1];
        String previous = currentRoom();
        if (previous.equals(targetRoom)) {
            System.out.println("Already in room " + targetRoom + ".");
            return;
        }
        Packet goodbye = identityPacket(MessageType.LEAVE).build();
        datagram.broadcast(peers.peerAddresses(previous, null), goodbye);
        room.set(targetRoom);
        System.out.println("Switched to room " + targetRoom + ". Requesting peers/history...");
        Packet announce = identityPacket(MessageType.HELLO)
                .put("clock", clock.tick())
                .put("askHistory", history.size(targetRoom) == 0 ? "1" : "0")
                .build();
        datagram.broadcast(peers.peerAddresses(currentRoom(), null), announce);
        if (discoveryService != null) {
            discoveryService.broadcastProbe();
        }
        historySync.requestFromAnyPeer();
    }

    private void handleSendFile(String[] parts) {
        if (parts.length < 2) {
            System.out.println("Usage: /sendfile <path>");
            return;
        }
        Path file = Path.of(parts[1]);
        try {
            String fileId = config.nodeId() + ":file:" + clock.tick();
            fileTransferManager.sendFile(file, fileId, this::broadcastFilePacket);
        } catch (IOException ioe) {
            System.out.println("Failed to send file: " + ioe.getMessage());
        }
    }

    private void triggerDiscovery() {
        if (discoveryService == null) {
            System.out.println("Discovery is disabled.");
            return;
        }
        discoveryService.broadcastProbe();
        System.out.println("Discovery probe broadcast.");
    }

    private void publishLocalMessage(String text, boolean robot) {
        publishMessage(text, nickname.get(), robot ? "[robot]" : null);
    }

    private void publishExternalMessage(String text, String nickOverride) {
        String author = (nickOverride == null || nickOverride.isBlank())
                ? nickname.get() + "-api"
                : nickOverride;
        publishMessage(text, author, "[api]");
    }

    private void publishMessage(String text, String nick, String prefix) {
        long lamport = clock.tick();
        String messageId = config.nodeId() + ":" + lamport;
        String activeRoom = currentRoom();
        ChatMessage message = new ChatMessage(
                messageId,
                config.nodeId(),
                nick,
                lamport,
                System.currentTimeMillis(),
                text,
                activeRoom);
        history.add(activeRoom, message);
        displayMessage(message, prefix);
        broadcastChat(message, true, false);
    }

    private void displayMessage(ChatMessage message, String prefix) {
        if (prefix == null) {
            System.out.println(message.toDisplayString());
        } else {
            System.out.println(prefix + " " + message.toDisplayString());
        }
        BufferedWriter writer = transcriptWriter.get();
        if (writer != null) {
            try {
                writer.write(message.toDisplayString());
                writer.newLine();
                writer.flush();
            } catch (IOException ioe) {
                System.err.println("Failed to write transcript: " + ioe.getMessage());
            }
        }
    }

    private Packet.Builder identityPacket(MessageType type) {
        return Packet.builder(type)
                .put("nodeId", config.nodeId().toString())
                .put("nick", nickname.get())
                .put("port", listenPort)
                .put("room", currentRoom());
    }

    private void broadcast(Packet packet) {
        datagram.broadcast(peers.peerAddresses(currentRoom(), null), packet);
    }

    private Packet packetForMessage(MessageType type, ChatMessage message, boolean relay, boolean historyMode) {
        return Packet.builder(type)
                .put("mid", message.messageId())
                .put("origin", message.originId().toString())
                .put("nick", message.nickname())
                .put("clock", message.lamport())
                .put("ts", message.timestamp())
                .put("text", message.text())
                .put("room", message.room())
                .put("relay", relay ? "1" : "0")
                .put("history", historyMode ? "1" : "0")
                .build();
    }

    private void handleExternalMessage(ExternalHttpServer.ExternalMessage message) {
        publishExternalMessage(message.text(), message.nickname());
    }

    private ExternalHttpServer.HealthSnapshot healthSnapshot() {
        return new ExternalHttpServer.HealthSnapshot(
                config.nodeId().toString(),
                nickname.get(),
                peers.peerAddresses(currentRoom(), null).size(),
                history.size(currentRoom()));
    }

    private void logSystem(String message) {
        System.out.println("[system] " + message);
    }

    private void broadcastChat(ChatMessage message, boolean relay, boolean historyMode) {
        Packet packet = packetForMessage(MessageType.CHAT, message, relay, historyMode);
        datagram.broadcast(peers.peerAddresses(message.room(), message.originId()), packet);
    }

    private void broadcastFilePacket(Packet packet) {
        datagram.broadcast(peers.peerAddresses(currentRoom(), null), packet);
    }

    private void handleDiscoveredPeer(InetSocketAddress address) {
        if (isSelf(address)) {
            return;
        }
        peers.addSeed(address);
        Packet hello = identityPacket(MessageType.HELLO)
                .put("clock", clock.current())
                .put("askHistory", history.size(currentRoom()) == 0 ? "1" : "0")
                .build();
        datagram.send(address, hello);
        stopDiscoveryTaskIfConnected();
    }

    private void stopDiscoveryTaskIfConnected() {
        boolean hasRoomPeer = peers.allPeers().stream()
                .anyMatch(info -> info.nodeId().isPresent()
                        && info.room().map(currentRoom()::equals).orElse(false));
        if (discoveryTask != null && hasRoomPeer) {
            discoveryTask.cancel(false);
            discoveryTask = null;
        }
    }

    private void broadcastPeers(List<InetSocketAddress> targets) {
        String activeRoom = currentRoom();
        String payload = peers.allPeers().stream()
                .filter(info -> info.room().map(activeRoom::equals).orElse(true))
                .map(info -> info.address().getHostString() + ":" + info.address().getPort())
                .collect(Collectors.joining(","));
        if (payload.isEmpty()) {
            return;
        }
        Packet packet = Packet.builder(MessageType.PEERS)
                .put("nodeId", config.nodeId().toString())
                .put("list", payload)
                .build();
        Iterable<InetSocketAddress> dest = targets == null ? peers.peerAddresses(activeRoom, null) : targets;
        datagram.broadcast(dest, packet);
    }

    private boolean isSelf(InetSocketAddress address) {
        if (address.getPort() != listenPort) {
            return false;
        }
        String host = address.getHostString().toLowerCase(Locale.ROOT);
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
    }

    private static Optional<InetSocketAddress> parseAddress(String spec) {
        String[] parts = spec.split(":");
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            return Optional.of(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private final class MessageRouter implements DatagramService.PacketProcessor {
        @Override
        public void handle(InetSocketAddress source, Packet packet) {
            try {
                dispatch(source, packet);
            } catch (Exception ex) {
                System.err.println("Failed to handle packet " + packet.type() + " from " + source + ": " + ex.getMessage());
            }
        }

        private void dispatch(InetSocketAddress source, Packet packet) {
            switch (packet.type()) {
                case HELLO -> onHello(source, packet);
                case WELCOME -> onWelcome(source, packet);
                case PEERS -> onPeers(packet);
                case CHAT -> onChat(source, packet);
                case HISTORY_REQ -> onHistoryReq(source, packet);
                case HISTORY_DONE -> onHistoryDone(source, packet);
                case HEARTBEAT -> onHeartbeat(packet);
                case LEAVE -> onLeave(packet);
                case RESEND_REQ -> onResendReq(source, packet);
                case RESEND_RES -> onChat(source, packet);
                case FILE_META -> fileTransferManager.handleMeta(packet);
                case FILE_CHUNK -> fileTransferManager.handleChunk(packet);
                case DISCOVER, DISCOVER_RES -> { /* discovery handled elsewhere */ }
                default -> System.err.println("Unhandled packet type " + packet.type());
            }
        }

        private void onHello(InetSocketAddress source, Packet packet) {
            UUID remoteId = UUID.fromString(packet.require("nodeId"));
            String nick = packet.require("nick");
            String remoteRoom = packet.getOrDefault("room", currentRoom());
            long remoteClock = Long.parseLong(packet.getOrDefault("clock", "0"));
            clock.observe(remoteClock);
            PeerInfo peer = peers.identifyPeer(source, remoteId, nick, remoteRoom);
            stopDiscoveryTaskIfConnected();
            if (currentRoom().equals(remoteRoom) && "1".equals(packet.getOrDefault("askHistory", "0"))) {
                historySync.sendSnapshot(peer);
            }
            Packet welcome = identityPacket(MessageType.WELCOME)
                    .put("clock", clock.current())
                    .build();
            datagram.send(source, welcome);
            if (currentRoom().equals(remoteRoom)) {
                broadcastPeers(List.of(source));
            }
        }

        private void onWelcome(InetSocketAddress source, Packet packet) {
            UUID remoteId = UUID.fromString(packet.require("nodeId"));
            String nick = packet.require("nick");
            String remoteRoom = packet.getOrDefault("room", currentRoom());
            long remoteClock = Long.parseLong(packet.getOrDefault("clock", "0"));
            clock.observe(remoteClock);
            PeerInfo peer = peers.identifyPeer(source, remoteId, nick, remoteRoom);
            stopDiscoveryTaskIfConnected();
            if (currentRoom().equals(remoteRoom)) {
                historySync.requestSnapshot(peer);
            }
        }

        private void onPeers(Packet packet) {
            String list = packet.getOrDefault("list", "");
            if (list.isEmpty()) {
                return;
            }
            Arrays.stream(list.split(","))
                    .map(String::trim)
                    .filter(entry -> !entry.isEmpty())
                    .map(ChatNode::parseAddress)
                    .flatMap(Optional::stream)
                    .filter(address -> !isSelf(address))
                    .forEach(address -> {
                        peers.addSeed(address);
                        Packet hello = identityPacket(MessageType.HELLO)
                                .put("clock", clock.current())
                                .put("askHistory", history.size(currentRoom()) == 0 ? "1" : "0")
                                .build();
                        datagram.send(address, hello);
                    });
        }

        private void onChat(InetSocketAddress source, Packet packet) {
            UUID origin = UUID.fromString(packet.require("origin"));
            String messageId = packet.require("mid");
            long remoteClock = Long.parseLong(packet.require("clock"));
            long timestamp = Long.parseLong(packet.require("ts"));
            String nick = packet.require("nick");
            String text = packet.require("text");
            String messageRoom = packet.getOrDefault("room", currentRoom());
            PeerInfo peer = peers.byNodeId(origin)
                    .orElseGet(() -> peers.identifyPeer(source, origin, nick, messageRoom));
            if (peer != null) {
                peer.updateRoom(messageRoom);
            }
            if (!currentRoom().equals(messageRoom)) {
                return;
            }
            clock.observe(remoteClock);
            ChatMessage message = new ChatMessage(messageId, origin, nick, remoteClock, timestamp, text, messageRoom);
            boolean fresh = history.add(messageRoom, message);
            if (fresh) {
                String marker = "1".equals(packet.getOrDefault("history", "0"))
                        ? "[history]"
                        : (packet.type() == MessageType.RESEND_RES ? "[resend]" : null);
                displayMessage(message, marker);
                if ("1".equals(packet.getOrDefault("relay", "1")) && packet.type() == MessageType.CHAT) {
                    Packet relayPacket = packetForMessage(MessageType.CHAT, message, true, false);
                    datagram.broadcast(peers.peerAddresses(messageRoom, origin), relayPacket);
                }
            }
        }

        private void onHistoryReq(InetSocketAddress source, Packet packet) {
            String reqRoom = packet.getOrDefault("room", currentRoom());
            PeerInfo peer = peers.byAddress(source).orElseGet(() -> peers.addSeed(source));
            peer.updateRoom(reqRoom);
            if (!currentRoom().equals(reqRoom)) {
                return;
            }
            historySync.sendSnapshot(peer);
        }

        private void onHistoryDone(InetSocketAddress source, Packet packet) {
            String remoteRoom = packet.getOrDefault("room", currentRoom());
            if (!currentRoom().equals(remoteRoom)) {
                return;
            }
            peers.byAddress(source).ifPresent(info -> {
                info.updateRoom(remoteRoom);
                info.markHistorySynced();
            });
        }

        private void onHeartbeat(Packet packet) {
            UUID remoteId = UUID.fromString(packet.require("nodeId"));
            long remoteClock = Long.parseLong(packet.getOrDefault("clock", "0"));
            String remoteRoom = packet.getOrDefault("room", null);
            clock.observe(remoteClock);
            peers.markHeartbeat(remoteId, remoteRoom);
        }

        private void onLeave(Packet packet) {
            UUID remoteId = UUID.fromString(packet.require("nodeId"));
            peers.forget(remoteId);
            String remoteRoom = packet.getOrDefault("room", "?");
            System.out.println("Peer " + remoteId + " left room " + remoteRoom + ".");
        }

        private void onResendReq(InetSocketAddress source, Packet packet) {
            String ids = packet.getOrDefault("ids", "");
            String reqRoom = packet.getOrDefault("room", currentRoom());
            if (ids.isEmpty()) {
                return;
            }
            if (!currentRoom().equals(reqRoom)) {
                return;
            }
            Arrays.stream(ids.split(","))
                    .map(String::trim)
                    .filter(id -> !id.isEmpty())
                    .forEach(id -> history.get(reqRoom, id).ifPresent(message -> {
                        Packet response = packetForMessage(MessageType.RESEND_RES, message, false, true);
                        datagram.send(source, response);
                    }));
        }
    }

    private final class HistorySyncService {
        void requestSnapshot(PeerInfo peer) {
            if (peer == null) {
                return;
            }
            if (peer.room().map(room -> !room.equals(currentRoom())).orElse(false)) {
                return;
            }
            Packet request = Packet.builder(MessageType.HISTORY_REQ)
                    .put("nodeId", config.nodeId().toString())
                    .put("since", history.size(currentRoom()))
                    .put("room", currentRoom())
                    .build();
            datagram.send(peer.address(), request);
        }

        void requestFromAnyPeer() {
            Optional<PeerInfo> peer = peers.allPeers().stream()
                    .filter(info -> info.room().map(currentRoom()::equals).orElse(true))
                    .findFirst();
            if (peer.isPresent()) {
                requestSnapshot(peer.get());
            } else {
                System.out.println("No peers available to sync from in room " + currentRoom() + ".");
            }
        }

        void sendSnapshot(PeerInfo peer) {
            if (peer == null) {
                return;
            }
            if (peer.room().map(room -> !room.equals(currentRoom())).orElse(false)) {
                return;
            }
            List<ChatMessage> snapshot = history.snapshot(currentRoom());
            for (ChatMessage message : snapshot) {
                Packet packet = packetForMessage(MessageType.CHAT, message, false, true);
                datagram.send(peer.address(), packet);
            }
            Packet done = Packet.builder(MessageType.HISTORY_DONE)
                    .put("nodeId", config.nodeId().toString())
                    .put("room", currentRoom())
                    .build();
            datagram.send(peer.address(), done);
        }

        void requestSpecific(String messageId) {
            Packet request = Packet.builder(MessageType.RESEND_REQ)
                    .put("nodeId", config.nodeId().toString())
                    .put("ids", messageId)
                    .put("room", currentRoom())
                    .build();
            broadcast(request);
        }
    }

    private final class RobotSpeaker {
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        private ScheduledFuture<?> task;

        void start(int seconds) {
            stop();
            task = executor.scheduleAtFixedRate(
                    () -> publishLocalMessage(randomUtterance(), true),
                    seconds,
                    seconds,
                    TimeUnit.SECONDS);
            System.out.println("Robot chatting every " + seconds + "s.");
        }

        void stop() {
            if (task != null) {
                task.cancel(true);
                task = null;
                System.out.println("Robot stopped.");
            }
        }

        private String randomUtterance() {
            return ROBOT_LINES.get((int) (Math.random() * ROBOT_LINES.size()));
        }

        void shutdown() {
            stop();
            executor.shutdownNow();
        }
    }

    private static final class ConsoleShell implements Runnable {
        private final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        private final Consumer<String> consumer;

        ConsoleShell(Consumer<String> consumer) {
            this.consumer = consumer;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    String line = reader.readLine();
                    if (line == null) {
                        consumer.accept(null);
                        return;
                    }
                    consumer.accept(line);
                } catch (IOException ioe) {
                    System.err.println("Console closed: " + ioe.getMessage());
                    consumer.accept(null);
                    return;
                }
            }
        }
    }
}
