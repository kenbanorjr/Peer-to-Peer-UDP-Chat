package csc4010.chat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable chat payload shared across the cluster.
 */
public final class ChatMessage implements Comparable<ChatMessage> {
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final String messageId;
    private final UUID originId;
    private final String nickname;
    private final long lamport;
    private final long timestamp;
    private final String text;
    private final String room;

    public ChatMessage(String messageId, UUID originId, String nickname, long lamport, long timestamp, String text, String room) {
        this.messageId = messageId;
        this.originId = originId;
        this.nickname = nickname;
        this.lamport = lamport;
        this.timestamp = timestamp;
        this.text = text;
        this.room = room;
    }

    public String messageId() {
        return messageId;
    }

    public UUID originId() {
        return originId;
    }

    public String nickname() {
        return nickname;
    }

    public long lamport() {
        return lamport;
    }

    public long timestamp() {
        return timestamp;
    }

    public String text() {
        return text;
    }

    public String room() {
        return room;
    }

    public String toDisplayString() {
        return "[" + FORMATTER.format(Instant.ofEpochMilli(timestamp)) + "] "
                + "(" + room + ") " + nickname + ": " + text;
    }

    @Override
    public int compareTo(ChatMessage other) {
        int cmp = Long.compare(this.lamport, other.lamport);
        if (cmp != 0) {
            return cmp;
        }
        cmp = this.originId.compareTo(other.originId);
        if (cmp != 0) {
            return cmp;
        }
        return this.messageId.compareTo(other.messageId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChatMessage that = (ChatMessage) o;
        return Objects.equals(messageId, that.messageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId);
    }
}
