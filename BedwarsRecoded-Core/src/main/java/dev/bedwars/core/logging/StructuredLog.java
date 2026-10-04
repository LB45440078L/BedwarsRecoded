package dev.bedwars.core.logging;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal JSON-lines logger. Emits one JSON object per event so log aggregation
 * (Loki/ELK) can index fields like {@code game_id}, {@code pod_id} and
 * {@code player_uuid} instead of parsing free text.
 *
 * <p>Deliberately dependency-free: Core must not pull in a logging framework, and
 * the plugin only needs "flat object to a string".
 */
public final class StructuredLog {

    private StructuredLog() {
    }

    public static Map<String, Object> fields() {
        return new LinkedHashMap<>();
    }

    /** Renders {@code {"event":"...","k":"v",...}} with proper escaping. */
    public static String json(String event, Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder(64);
        sb.append('{');
        appendField(sb, "event", event);
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            sb.append(',');
            appendField(sb, entry.getKey(), entry.getValue());
        }
        return sb.append('}').toString();
    }

    private static void appendField(StringBuilder sb, String key, Object value) {
        sb.append(quote(key)).append(':');
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            sb.append(quote(String.valueOf(value)));
        }
    }

    private static String quote(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 2);
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}