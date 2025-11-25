package csc4010.chat;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class Packet {
    private final MessageType type;
    private final Map<String, String> fields;

    public Packet(MessageType type, Map<String, String> fields) {
        this.type = Objects.requireNonNull(type, "type");
        this.fields = Collections.unmodifiableMap(new HashMap<>(fields));
    }

    public MessageType type() {
        return type;
    }

    public Map<String, String> fields() {
        return fields;
    }

    public String require(String key) {
        String val = fields.get(key);
        if (val == null) {
            throw new IllegalArgumentException("Missing field " + key + " for packet " + type);
        }
        return val;
    }

    public String getOrDefault(String key, String fallback) {
        return fields.getOrDefault(key, fallback);
    }

    public static Builder builder(MessageType type) {
        return new Builder(type);
    }

    public static final class Builder {
        private final MessageType type;
        private final Map<String, String> fields = new HashMap<>();

        private Builder(MessageType type) {
            this.type = type;
        }

        public Builder put(String key, String value) {
            fields.put(key, value);
            return this;
        }

        public Builder put(String key, long value) {
            return put(key, Long.toString(value));
        }

        public Packet build() {
            return new Packet(type, fields);
        }
    }
}
