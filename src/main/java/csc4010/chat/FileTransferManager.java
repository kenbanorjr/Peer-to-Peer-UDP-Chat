package csc4010.chat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Handles binary file transfer (chunking outbound files and reassembling inbound ones).
 */
public final class FileTransferManager {
    private static final int CHUNK_SIZE = 6144;
    private final Path downloadDirectory;
    private final Consumer<String> notifier;
    private final Map<String, IncomingFile> incoming = new ConcurrentHashMap<>();

    public FileTransferManager(Path downloadDirectory, Consumer<String> notifier) throws IOException {
        this.downloadDirectory = downloadDirectory;
        this.notifier = notifier;
        Files.createDirectories(downloadDirectory);
    }

    public void sendFile(Path file, String fileId, Consumer<Packet> sender) throws IOException {
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            throw new IOException("File not found: " + file);
        }
        long size = Files.size(file);
        int chunkCount = (int) Math.ceil((double) size / CHUNK_SIZE);
        Packet meta = Packet.builder(MessageType.FILE_META)
                .put("fileId", fileId)
                .put("name", file.getFileName().toString())
                .put("size", size)
                .put("chunks", chunkCount)
                .build();
        sender.accept(meta);
        notifier.accept("Sending file " + file.getFileName() + " (" + size + " bytes) in " + chunkCount + " chunks.");
        try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int seq = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                byte[] payload = (read == buffer.length) ? buffer : Arrays.copyOf(buffer, read);
                Packet chunk = Packet.builder(MessageType.FILE_CHUNK)
                        .put("fileId", fileId)
                        .put("seq", seq)
                        .put("data", Base64.getEncoder().encodeToString(payload))
                        .build();
                sender.accept(chunk);
                seq++;
            }
        }
        notifier.accept("File transfer scheduled for " + file.getFileName());
    }

    public void handleMeta(Packet packet) {
        String fileId = packet.require("fileId");
        String name = packet.require("name");
        int chunks = Integer.parseInt(packet.require("chunks"));
        Path target = resolveUniqueTarget(name);
        incoming.put(fileId, new IncomingFile(target, chunks));
        notifier.accept("Receiving file '" + name + "' -> " + target + " (" + chunks + " chunks).");
    }

    public void handleChunk(Packet packet) {
        String fileId = packet.require("fileId");
        IncomingFile file = incoming.get(fileId);
        if (file == null) {
            return;
        }
        int seq = Integer.parseInt(packet.require("seq"));
        byte[] data = Base64.getDecoder().decode(packet.require("data"));
        if (file.addChunk(seq, data)) {
            if (file.isComplete()) {
                try {
                    writeToDisk(file);
                    notifier.accept("Saved file to " + file.target());
                } catch (IOException ioe) {
                    notifier.accept("Failed to save file " + file.target() + ": " + ioe.getMessage());
                } finally {
                    incoming.remove(fileId);
                }
            }
        }
    }

    private void writeToDisk(IncomingFile file) throws IOException {
        Files.createDirectories(file.target().getParent());
        try (OutputStream output = Files.newOutputStream(
                file.target(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            for (int i = 0; i < file.totalChunks(); i++) {
                Optional<byte[]> maybeChunk = file.chunk(i);
                if (maybeChunk.isEmpty()) {
                    throw new IOException("Missing chunk " + i);
                }
                output.write(maybeChunk.get());
            }
        }
    }

    private Path resolveUniqueTarget(String name) {
        Path candidate = downloadDirectory.resolve(name);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        int suffix = 1;
        String baseName = name;
        String extension = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            baseName = name.substring(0, dot);
            extension = name.substring(dot);
        }
        while (Files.exists(candidate)) {
            candidate = downloadDirectory.resolve(baseName + "-" + suffix + extension);
            suffix++;
        }
        return candidate;
    }

    private static final class IncomingFile {
        private final Path target;
        private final int totalChunks;
        private final byte[][] chunks;
        private final AtomicInteger stored = new AtomicInteger(0);

        IncomingFile(Path target, int totalChunks) {
            this.target = target;
            this.totalChunks = totalChunks;
            this.chunks = new byte[totalChunks][];
        }

        Path target() {
            return target;
        }

        int totalChunks() {
            return totalChunks;
        }

        boolean addChunk(int seq, byte[] data) {
            if (seq < 0 || seq >= totalChunks) {
                return false;
            }
            if (chunks[seq] == null) {
                chunks[seq] = data;
                stored.incrementAndGet();
                return true;
            }
            return false;
        }

        boolean isComplete() {
            return stored.get() == totalChunks;
        }

        Optional<byte[]> chunk(int seq) {
            if (seq < 0 || seq >= totalChunks) {
                return Optional.empty();
            }
            return Optional.ofNullable(chunks[seq]);
        }
    }
}
