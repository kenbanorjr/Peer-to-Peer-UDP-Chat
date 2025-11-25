package csc4010.chat;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry of peers keyed by network address and node id.
 */
public final class PeerRegistry {
    private final Map<String, PeerInfo> byKey = new ConcurrentHashMap<>();
    private final Map<UUID, PeerInfo> byId = new ConcurrentHashMap<>();

    public PeerInfo addSeed(InetSocketAddress address) {
        return byKey.computeIfAbsent(key(address), ignored -> new PeerInfo(address));
    }

    public PeerInfo identifyPeer(InetSocketAddress address, UUID nodeId, String nickname, String room) {
        PeerInfo info = addSeed(address);
        info.identify(nodeId, nickname, room);
        byId.put(nodeId, info);
        return info;
    }

    public void forget(UUID nodeId) {
        PeerInfo info = byId.remove(nodeId);
        if (info != null) {
            byKey.remove(key(info.address()));
        }
    }

    public void markHeartbeat(UUID nodeId, String room) {
        PeerInfo info = byId.get(nodeId);
        if (info != null) {
            if (room != null) {
                info.updateRoom(room);
            }
            info.touch();
        }
    }

    public Optional<PeerInfo> byAddress(InetSocketAddress address) {
        return Optional.ofNullable(byKey.get(key(address)));
    }

    public Optional<PeerInfo> byNodeId(UUID nodeId) {
        return Optional.ofNullable(byId.get(nodeId));
    }

    public Collection<PeerInfo> allPeers() {
        return Collections.unmodifiableCollection(byKey.values());
    }

    public List<InetSocketAddress> peerAddresses(String room, UUID exclude) {
        List<InetSocketAddress> addresses = new ArrayList<>();
        for (PeerInfo info : byKey.values()) {
            if (exclude != null && info.nodeId().map(exclude::equals).orElse(false)) {
                continue;
            }
            if (room != null) {
                String peerRoom = info.room().orElse(null);
                if (peerRoom != null && !peerRoom.equals(room)) {
                    continue;
                }
            }
            addresses.add(info.address());
        }
        return addresses;
    }

    public void evictStale(Duration maxAge) {
        Instant deadline = Instant.now().minus(maxAge);
        Set<UUID> stale = byId.entrySet().stream()
                .filter(entry -> entry.getValue().lastSeen().isBefore(deadline))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        stale.forEach(this::forget);
    }

    private static String key(InetSocketAddress address) {
        return address.getHostString() + ":" + address.getPort();
    }
}
