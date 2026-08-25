package burpmcp.transport;

/** One dispatched SSE event. {@code event} is null for a default "message" event. */
public record SseEvent(String event, String data, String id) {

    public String eventOrDefault() {
        return event != null ? event : "message";
    }
}
