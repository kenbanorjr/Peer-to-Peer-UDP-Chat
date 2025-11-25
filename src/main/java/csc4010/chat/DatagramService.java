package csc4010.chat;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thin wrapper around {@link DatagramSocket} handling the receive loop.
 */
public final class DatagramService implements Closeable {
    private final DatagramSocket socket;
    private final PacketProcessor processor;
    private final DropSimulator dropSimulator;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean();

    public DatagramService(int port, PacketProcessor processor, DropSimulator dropSimulator) throws SocketException {
        this.socket = new DatagramSocket(port);
        this.socket.setSoTimeout(1000);
        this.processor = processor;
        this.dropSimulator = dropSimulator;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        executor.submit(this::receiveLoop);
    }

    private void receiveLoop() {
        byte[] buffer = new byte[65535];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (running.get()) {
            try {
                socket.receive(packet);
                if (dropSimulator.shouldDropInbound()) {
                    continue;
                }
                String payload = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                InetSocketAddress source = new InetSocketAddress(packet.getAddress(), packet.getPort());
                processor.handle(source, PacketCodec.decode(payload));
            } catch (SocketTimeoutException timeout) {
                // Expected: loop again to check running flag.
            } catch (IOException ioe) {
                if (running.get()) {
                    System.err.println("Receive error: " + ioe.getMessage());
                }
            } catch (IllegalArgumentException malformed) {
                System.err.println("Discarded malformed packet: " + malformed.getMessage());
            }
            Arrays.fill(buffer, (byte) 0);
        }
    }

    public void send(InetSocketAddress destination, Packet packet) {
        if (dropSimulator.shouldDropOutbound()) {
            return;
        }
        byte[] payload = PacketCodec.encode(packet).getBytes(StandardCharsets.UTF_8);
        DatagramPacket datagram = new DatagramPacket(payload, payload.length, destination);
        try {
            socket.send(datagram);
        } catch (IOException ioe) {
            System.err.println("Send error to " + destination + ": " + ioe.getMessage());
        }
    }

    public void broadcast(Iterable<InetSocketAddress> destinations, Packet packet) {
        for (InetSocketAddress destination : destinations) {
            send(destination, packet);
        }
    }

    public int localPort() {
        return socket.getLocalPort();
    }

    @Override
    public void close() {
        running.set(false);
        socket.close();
        executor.shutdownNow();
    }

    @FunctionalInterface
    public interface PacketProcessor {
        void handle(InetSocketAddress source, Packet packet);
    }
}
