package burpmcp.transport;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseEventParserTest {

    @Test
    void singleLineDataDispatchesOnBlankLine() {
        SseEventParser parser = new SseEventParser();
        assertTrue(parser.feedLine("data: hello").isEmpty());
        Optional<SseEvent> event = parser.feedLine("");
        assertTrue(event.isPresent());
        assertEquals("hello", event.get().data());
        assertEquals("message", event.get().eventOrDefault());
    }

    @Test
    void multiLineDataIsJoinedWithNewlines() {
        SseEventParser parser = new SseEventParser();
        parser.feedLine("data: line one");
        parser.feedLine("data: line two");
        Optional<SseEvent> event = parser.feedLine("");
        assertTrue(event.isPresent());
        assertEquals("line one\nline two", event.get().data());
    }

    @Test
    void namedEndpointEventIsCaptured() {
        SseEventParser parser = new SseEventParser();
        parser.feedLine("event: endpoint");
        parser.feedLine("data: /messages?sessionId=abc123");
        Optional<SseEvent> event = parser.feedLine("");
        assertTrue(event.isPresent());
        assertEquals("endpoint", event.get().event());
        assertEquals("/messages?sessionId=abc123", event.get().data());
    }

    @Test
    void idFieldIsCapturedForResumability() {
        SseEventParser parser = new SseEventParser();
        parser.feedLine("id: 42");
        parser.feedLine("data: payload");
        Optional<SseEvent> event = parser.feedLine("");
        assertTrue(event.isPresent());
        assertEquals("42", event.get().id());
    }

    @Test
    void commentLinesAreIgnored() {
        SseEventParser parser = new SseEventParser();
        assertTrue(parser.feedLine(": this is a comment").isEmpty());
        assertTrue(parser.feedLine("data: x").isEmpty());
        Optional<SseEvent> event = parser.feedLine("");
        assertTrue(event.isPresent());
        assertEquals("x", event.get().data());
    }

    @Test
    void blankLineWithNoAccumulatedContentDoesNotDispatch() {
        SseEventParser parser = new SseEventParser();
        assertFalse(parser.feedLine("").isPresent());
    }
}
