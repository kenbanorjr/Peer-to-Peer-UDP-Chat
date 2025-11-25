package csc4010.chat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Compact, dependency-free codec for packets.
 */
public final class PacketCodec {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private PacketCodec() {}

    public static String encode(Packet packet) {
        StringBuilder builder = new StringBuilder(packet.type().name());
        for (Map.Entry<String, String> entry : packet.fields().entrySet()) {
            builder.append('|').append(entry.getKey()).append('=')
                    .append(ENCODER.encodeToString(entry.getValue().getBytes(StandardCharsets.UTF_8)));
        }
        return builder.toString();
    }

    public static Packet decode(String frame) {
        String[] parts = frame.split("\\|");
        if (parts.length == 0) {
            throw new IllegalArgumentException("Empty packet");
        }
        MessageType type = MessageType.valueOf(parts[0]);
        Map<String, String> fields = new HashMap<>();
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = part.substring(0, eq);
            String encoded = part.substring(eq + 1);
            String value = new String(DECODER.decode(encoded), StandardCharsets.UTF_8);
            fields.put(key, value);
        }
        return new Packet(type, fields);
    }
}
