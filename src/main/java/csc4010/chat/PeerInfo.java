package csc4010.chat;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Details tracked about each known peer.
 */
public final class PeerInfo {
    public enum Status {SEEDING, ACTIVE, SUSPECT}

    private final InetSocketAddress address;
    private UUID nodeId;
    private String nickname;
    private String room;
    private volatile Instant lastSeen = Instant.now();
    private volatile Status status = Status.SEEDING;
    private volatile boolean historySynced;

    public PeerInfo(InetSocketAddress address) {
        this.address = address;
    }

    public InetSocketAddress address() {
        return address;
    }

    public Optional<UUID> nodeId() {
        return Optional.ofNullable(nodeId);
    }

    public Optional<String> nickname() {
        return Optional.ofNullable(nickname);
    }

    public Optional<String> room() {
        return Optional.ofNullable(room);
    }

    public Instant lastSeen() {
        return lastSeen;
    }

    public Status status() {
        return status;
    }

    public boolean historySynced() {
        return historySynced;
    }

    public void markHistorySynced() {
        this.historySynced = true;
    }

    public void identify(UUID nodeId, String nickname, String room) {
        this.nodeId = nodeId;
        this.nickname = nickname;
        this.room = room;
        this.status = Status.ACTIVE;
        touch();
    }

    public void updateRoom(String room) {
        this.room = room;
    }

    public void touch() {
        this.lastSeen = Instant.now();
    }

    public void markSuspect() {
        this.status = Status.SUSPECT;
    }

    public String describe() {
        return address + " id=" + (nodeId != null ? nodeId : "?") + " nick=" + (nickname != null ? nickname : "?")
                + " room=" + (room != null ? room : "?") + " status=" + status;
    }
}
