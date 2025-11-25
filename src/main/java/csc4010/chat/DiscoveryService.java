package csc4010.chat;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Lightweight UDP broadcast discovery so nodes can find peers without manual seeds.
 */
public final class DiscoveryService implements Closeable {
    private final DatagramSocket socket;
    private final int discoveryPort;
    private final int listenPort;
    private final String nodeId;
    private final Supplier<String> nicknameSupplier;
    private final Consumer<InetSocketAddress> discoveredPeerConsumer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DiscoveryService(
            int discoveryPort,
            int listenPort,
            String nodeId,
            Supplier<String> nicknameSupplier,
            Consumer<InetSocketAddress> discoveredPeerConsumer) throws SocketException {
        this.discoveryPort = discoveryPort;
        this.listenPort = listenPort;
        this.nodeId = nodeId;
        this.nicknameSupplier = nicknameSupplier;
        this.discoveredPeerConsumer = discoveredPeerConsumer;

        this.socket = new DatagramSocket(null);
        this.socket.setReuseAddress(true);
        this.socket.setBroadcast(true);
        this.socket.bind(new InetSocketAddress(discoveryPort));
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        executor.submit(this::receiveLoop);
    }

    private void receiveLoop() {
        byte[] buffer = new byte[4096];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (running.get()) {
            try {
                socket.receive(packet);
                String payload = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                handle(packet, payload);
            } catch (IOException ioe) {
                if (running.get()) {
                    System.err.println("Discovery receive error: " + ioe.getMessage());
                }
            }
        }
    }

    private void handle(DatagramPacket packet, String payload) {
        try {
            Packet decoded = PacketCodec.decode(payload);
            if (decoded.type() == MessageType.DISCOVER) {
                String remoteId = decoded.require("nodeId");
                if (nodeId.equals(remoteId)) {
                    return;
                }
                respondToDiscovery(packet.getAddress());
            } else if (decoded.type() == MessageType.DISCOVER_RES) {
                String remoteId = decoded.require("nodeId");
                if (nodeId.equals(remoteId)) {
                    return;
                }
                int port = Integer.parseInt(decoded.require("port"));
                InetSocketAddress address = new InetSocketAddress(packet.getAddress(), port);
                discoveredPeerConsumer.accept(address);
            }
        } catch (IllegalArgumentException ignored) {
            // Ignore malformed discovery packets.
        }
    }

    private void respondToDiscovery(InetAddress requester) {
        Packet packet = Packet.builder(MessageType.DISCOVER_RES)
                .put("nodeId", nodeId)
                .put("nick", nicknameSupplier.get())
                .put("port", listenPort)
                .build();
        byte[] data = PacketCodec.encode(packet).getBytes(StandardCharsets.UTF_8);
        DatagramPacket response = new DatagramPacket(data, data.length, new InetSocketAddress(requester, discoveryPort));
        try {
            socket.send(response);
        } catch (IOException ioe) {
            System.err.println("Discovery respond error: " + ioe.getMessage());
        }
    }

    public void broadcastProbe() {
        Packet packet = Packet.builder(MessageType.DISCOVER)
                .put("nodeId", nodeId)
                .put("nick", nicknameSupplier.get())
                .put("port", listenPort)
                .build();
        byte[] data = PacketCodec.encode(packet).getBytes(StandardCharsets.UTF_8);
        try {
            DatagramPacket datagram = new DatagramPacket(
                    data,
                    data.length,
                    new InetSocketAddress("255.255.255.255", discoveryPort));
            socket.send(datagram);
        } catch (IOException ioe) {
            System.err.println("Discovery probe failed: " + ioe.getMessage());
        }
    }

    @Override
    public void close() {
        running.set(false);
        socket.close();
        executor.shutdownNow();
    }
}
