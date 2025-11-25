package csc4010.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Stores chat messages in Lamport order and deduplicates by id.
 */
public final class MessageStore {
    private static final class RoomStore {
        private final ConcurrentHashMap<String, ChatMessage> byId = new ConcurrentHashMap<>();
        private final NavigableSet<ChatMessage> ordered = new ConcurrentSkipListSet<>();
    }

    private final ConcurrentHashMap<String, RoomStore> byRoom = new ConcurrentHashMap<>();

    private RoomStore room(String room) {
        return byRoom.computeIfAbsent(room, key -> new RoomStore());
    }

    public boolean add(String room, ChatMessage message) {
        RoomStore store = room(room);
        ChatMessage prior = store.byId.putIfAbsent(message.messageId(), message);
        if (prior == null) {
            store.ordered.add(message);
            return true;
        }
        return false;
    }

    public Optional<ChatMessage> get(String room, String messageId) {
        return Optional.ofNullable(room(room).byId.get(messageId));
    }

    public List<ChatMessage> snapshot(String room) {
        return new ArrayList<>(room(room).ordered);
    }

    public void clear(String room) {
        RoomStore store = byRoom.get(room);
        if (store != null) {
            store.byId.clear();
            store.ordered.clear();
        }
    }

    public boolean remove(String room, String messageId) {
        RoomStore store = room(room);
        ChatMessage removed = store.byId.remove(messageId);
        if (removed != null) {
            store.ordered.remove(removed);
            return true;
        }
        return false;
    }

    public int size(String room) {
        return room(room).ordered.size();
    }
}
