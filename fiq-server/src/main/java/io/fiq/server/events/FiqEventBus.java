package io.fiq.server.events;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@ApplicationScoped
public class FiqEventBus {
    private final CopyOnWriteArrayList<MultiEmitter<? super Event>> emitters =
            new CopyOnWriteArrayList<>();

    public Multi<Event> stream(UUID workspaceId) {
        return Multi.createFrom()
                .emitter(
                        emitter -> {
                            emitters.add(emitter);
                            emitter.onTermination(() -> emitters.remove(emitter));
                            emitter.emit(
                                    new Event(
                                            UUID.randomUUID(),
                                            workspaceId,
                                            "CONNECTED",
                                            Instant.now(),
                                            Map.of("message", "FIQ event stream connected")));
                        });
    }

    public void publish(UUID workspaceId, String type, Map<String, Object> payload) {
        var event =
                new Event(UUID.randomUUID(), workspaceId, type, Instant.now(), Map.copyOf(payload));
        for (var emitter : emitters) {
            if (!emitter.isCancelled()) emitter.emit(event);
        }
    }

    public record Event(
            UUID id,
            UUID workspaceId,
            String type,
            Instant occurredAt,
            Map<String, Object> payload) {}
}
