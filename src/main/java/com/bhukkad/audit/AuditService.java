package com.bhukkad.audit;

import com.bhukkad.entity.User;
import com.bhukkad.logging.TraceContext;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.util.RequestUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Best-effort writer for the append-only audit trail.
 *
 * <p>Auditing must never break the business operation it records, so every failure (actor
 * resolution, request lookup, repository save) is logged as a warning and swallowed.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository auditEventRepository;
    private final SecurityUtils securityUtils;

    /**
     * Records an audit event, resolving the actor from the current security context.
     *
     * @param action       what happened, e.g. {@code REFUND}
     * @param resourceType kind of resource, e.g. {@code PAYMENT}
     * @param resourceId   identifier of the resource, or {@code null}
     * @param oldState     state snapshot before the change, or {@code null}
     * @param newState     state snapshot after the change, or {@code null}
     */
    public void record(String action, String resourceType, String resourceId, String oldState, String newState) {
        save(action, resourceType, resourceId, oldState, newState, resolveCurrentActor());
    }

    /**
     * Records an audit event with an explicit actor id. Falls back to resolving the actor from the
     * security context when the supplied id is {@code null}.
     */
    public void recordEvent(String action, String resourceType, String resourceId, String oldState,
                            String newState, Long actorId) {
        save(action, resourceType, resourceId, oldState, newState,
                actorId != null ? new ActorInfo(actorId, null) : resolveCurrentActor());
    }

    private void save(String action, String resourceType, String resourceId, String oldState,
                      String newState, ActorInfo actor) {
        try {
            AuditEvent event = new AuditEvent();
            event.setActorId(actor.actorId());
            event.setActorRole(actor.actorRole());
            event.setAction(action);
            event.setResourceType(resourceType);
            event.setResourceId(resourceId);
            event.setOldState(oldState);
            event.setNewState(newState);
            event.setIpAddress(RequestUtils.resolveClientIp());
            event.setTraceId(TraceContext.getTraceId());
            event.setRequestId(TraceContext.getRequestId());
            auditEventRepository.save(event);
        } catch (Exception ex) {
            log.warn("Failed to record audit event | action={} | resourceType={} | resourceId={}",
                    action, resourceType, resourceId, ex);
        }
    }

    private ActorInfo resolveCurrentActor() {
        try {
            User user = securityUtils.getCurrentUser();
            if (user != null) {
                return new ActorInfo(user.getId(), user.getRole() != null ? user.getRole().name() : null);
            }
        } catch (Exception ex) {
            log.debug("No authenticated actor available for audit event: {}", ex.getMessage());
        }
        return new ActorInfo(null, null);
    }

    private record ActorInfo(Long actorId, String actorRole) {
    }
}
