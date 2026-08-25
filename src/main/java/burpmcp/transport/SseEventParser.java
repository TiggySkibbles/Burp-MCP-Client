package burpmcp.transport;

import java.util.Optional;

/**
 * Incremental parser for the SSE wire format (WHATWG "Server-Sent Events"), fed one line at a
 * time from a {@code BodyHandlers.ofLines()} stream. Shared by both {@link StreamableHttpTransport}
 * and {@link LegacySseTransport} since both eras use the same event framing.
 */
public final class SseEventParser {

    private final StringBuilder dataBuffer = new StringBuilder();
    private String eventType;
    private String lastId;
    private boolean hasContent;

    /** Feed one line (without its line terminator). Returns a dispatched event on a blank line, else empty. */
    public Optional<SseEvent> feedLine(String line) {
        if (line.isEmpty()) {
            if (!hasContent) {
                return Optional.empty();
            }
            SseEvent event = new SseEvent(eventType, dataBuffer.toString(), lastId);
            dataBuffer.setLength(0);
            eventType = null;
            hasContent = false;
            return Optional.of(event);
        }
        if (line.startsWith(":")) {
            return Optional.empty();
        }

        int colonIdx = line.indexOf(':');
        String field = colonIdx == -1 ? line : line.substring(0, colonIdx);
        String value = colonIdx == -1 ? "" : line.substring(colonIdx + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }

        switch (field) {
            case "event" -> {
                eventType = value;
                hasContent = true;
            }
            case "data" -> {
                if (dataBuffer.length() > 0) {
                    dataBuffer.append('\n');
                }
                dataBuffer.append(value);
                hasContent = true;
            }
            case "id" -> lastId = value;
            case "retry" -> {
                // MVP does not act on server-suggested reconnection delay.
            }
            default -> {
                // Unknown field per spec: ignore.
            }
        }
        return Optional.empty();
    }
}
