package watch.nabu.infrastructure.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe, organization-scoped registry of active {@link SseEmitter} instances.
 *
 * <p>Emitters are grouped by organization ID so that scan-result broadcasts
 * reach only clients belonging to the same organization — preventing
 * cross-organization data leakage.
 */
@Slf4j
@Component
public class SseEmitterRegistry {

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emittersByOrg =
            new ConcurrentHashMap<>();

    /**
     * Registers a new SSE emitter for the given organization.
     */
    public SseEmitter register(long timeoutMs, UUID orgId) {
        SseEmitter emitter = new SseEmitter(timeoutMs);

        CopyOnWriteArrayList<SseEmitter> orgEmitters =
                emittersByOrg.computeIfAbsent(orgId, k -> new CopyOnWriteArrayList<>());
        orgEmitters.add(emitter);
        log.debug("SSE client connected — orgId={} active emitters for org: {}",
                orgId, orgEmitters.size());

        Runnable remove = () -> {
            orgEmitters.remove(emitter);
            if (orgEmitters.isEmpty()) {
                emittersByOrg.remove(orgId, orgEmitters);
            }
            log.debug("SSE client disconnected — orgId={}", orgId);
        };

        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete();  // signal Tomcat that the async request is done
        });
        emitter.onError(ex -> {
            remove.run();
            emitter.completeWithError(ex);
        });

        return emitter;
    }

    /**
     * Sends {@code event} only to emitters registered for the given organization.
     * Emitters that fail to receive the event are removed.
     */
    public void broadcast(UUID orgId, SseEmitter.SseEventBuilder event) {
        CopyOnWriteArrayList<SseEmitter> orgEmitters = emittersByOrg.get(orgId);
        if (orgEmitters == null || orgEmitters.isEmpty()) {
            return;
        }
        java.util.List<SseEmitter> broken = null;
        for (SseEmitter emitter : orgEmitters) {
            try {
                emitter.send(event);
            } catch (Exception ex) {
                log.debug("Removing stale SSE emitter for orgId={}: {}", orgId, ex.getMessage());
                emitter.completeWithError(ex);
                if (broken == null) broken = new java.util.ArrayList<>();
                broken.add(emitter);
            }
        }
        if (broken != null) orgEmitters.removeAll(broken);
    }
}
