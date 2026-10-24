package org.fleetloom.gateway.push;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.fleetloom.common.event.EventEnvelope;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fans events out to every connected browser as {@code {"type", "occurredAt", "payload"}} text frames.
 * Push is best effort: a client that falls behind loses its oldest messages, and one that reconnects
 * gets only what happens next, so pages reload their data after (re)connecting.
 */
public final class PushHub implements WebSocketHandler {

    /** Messages kept per client while its connection is slow before the oldest are dropped. */
    static final int CLIENT_BUFFER = 512;

    private final Sinks.Many<String> sink = Sinks.many().multicast().directBestEffort();
    private final AtomicInteger sessions = new AtomicInteger();
    private final JsonMapper json;

    public PushHub(JsonMapper json) {
        this.json = json;
    }

    public record PushMessage(String type, Instant occurredAt, JsonNode payload) {
    }

    /** Called from the event bus listener threads, one per event type. */
    public synchronized void broadcast(EventEnvelope<JsonNode> event) {
        sink.tryEmitNext(json.writeValueAsString(new PushMessage(event.type(), event.occurredAt(), event.payload())));
    }

    public int sessionCount() {
        return sessions.get();
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        var outgoing = sink.asFlux()
                .onBackpressureBuffer(CLIENT_BUFFER, dropped -> { }, BufferOverflowStrategy.DROP_OLDEST)
                .map(session::textMessage);
        // the client sends nothing; its side of the stream ends when it disconnects
        return Mono.firstWithSignal(session.send(outgoing), session.receive().then())
                .doOnSubscribe(s -> sessions.incrementAndGet())
                .doFinally(s -> sessions.decrementAndGet());
    }
}
