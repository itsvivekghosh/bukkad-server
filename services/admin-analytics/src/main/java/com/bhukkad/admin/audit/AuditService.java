package com.bhukkad.admin.audit;

import com.bhukkad.admin.domain.AuditEvent;
import com.bhukkad.admin.domain.AuditEventRepository;
import com.bhukkad.common.web.RequestUtils;
import com.bhukkad.common.tracing.TraceContext;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private final AuditEventRepository auditEventRepository;

    public AuditService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    public void record(String action, String resourceType, String resourceId, String oldState, String newState) {
        save(action, resourceType, resourceId, oldState, newState, null);
    }

    public void recordEvent(String action, String resourceType, String resourceId, String oldState,
                            String newState, Long actorId) {
        save(action, resourceType, resourceId, oldState, newState, actorId);
    }

    private void save(String action, String resourceType, String resourceId, String oldState,
                      String newState, Long actorId) {
        try {
            AuditEvent event = new AuditEvent();
            event.setActorId(actorId);
            event.setAction(action);
            event.setEntityType(resourceType);
            if (resourceId != null) {
                try {
                    event.setEntityId(Long.valueOf(resourceId));
                } catch (NumberFormatException e) {
                    event.setResourceId(resourceId);
                }
            }
            event.setOldState(oldState);
            event.setNewState(newState);
            event.setIpAddress(RequestUtils.resolveClientIp());
            event.setTraceId(TraceContext.currentTraceId());
            event.setRequestId(TraceContext.currentRequestId());
            auditEventRepository.save(event);
        } catch (Exception ex) {
        }
    }
}
