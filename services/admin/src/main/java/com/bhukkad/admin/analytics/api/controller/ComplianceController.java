package com.bhukkad.admin.analytics.api.controller;

import com.bhukkad.admin.analytics.domain.entity.AuditEvent;
import com.bhukkad.admin.analytics.domain.repository.AuditEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Admin compliance API: searchable, exportable audit logs for DPDP/GDPR.
 * Protected by existing admin authorization.
 */
@RestController
@RequestMapping("/api/v1/compliance")
@PreAuthorize("hasRole('ADMIN')")
public class ComplianceController {

    private static final String SIGNING_ALGORITHM = "HmacSHA256";
    // In production supply a secret via configuration/secrets manager; this
    // default provides a deterministic signature so exports are verifiable.
    private static final String DEFAULT_EXPORT_SIGNING_KEY = "bhukkad-compliance-audit-export-v1";

    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;

    public ComplianceController(AuditEventRepository auditEventRepository, ObjectMapper objectMapper) {
        this.auditEventRepository = auditEventRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/audit-logs")
    public List<AuditEvent> searchAuditLogs(
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return auditEventRepository.findAll(buildSpec(actorId, resourceType, resourceId, action, from, to));
    }

    @GetMapping("/audit-logs/export")
    public ResponseEntity<String> exportAuditLogs(
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "json") String format) throws Exception {
        List<AuditEvent> events = auditEventRepository.findAll(buildSpec(actorId, resourceType, resourceId, action, from, to));
        String body;
        String contentType;
        String filename;
        if ("csv".equalsIgnoreCase(format)) {
            body = toCsv(events);
            contentType = "text/csv";
            filename = "audit-logs.csv";
        } else {
            body = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(events);
            contentType = MediaType.APPLICATION_JSON_VALUE;
            filename = "audit-logs.json";
        }
        String signature = sign(body);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header("X-Export-Signature", signature)
                .header("X-Export-Signature-Algorithm", SIGNING_ALGORITHM)
                .body(body);
    }

    private Specification<AuditEvent> buildSpec(Long actorId, String resourceType, String resourceId,
                                                String action, LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (actorId != null) {
                predicates.add(cb.equal(root.get("actorId"), actorId));
            }
            if (resourceType != null && !resourceType.isBlank()) {
                predicates.add(cb.equal(root.get("resourceType"), resourceType));
            }
            if (resourceId != null && !resourceId.isBlank()) {
                predicates.add(cb.equal(root.get("resourceId"), resourceId));
            }
            if (action != null && !action.isBlank()) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            query.orderBy(cb.asc(root.get("createdAt")));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private String toCsv(List<AuditEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("id,actorId,actorType,action,resourceType,resourceId,entityType,entityId,decision,ipAddress,traceId,requestId,createdAt\n");
        for (AuditEvent e : events) {
            sb.append(csv(e.getId())).append(',')
              .append(csv(e.getActorId())).append(',')
              .append(csv(e.getActorType())).append(',')
              .append(csv(e.getAction())).append(',')
              .append(csv(e.getResourceType())).append(',')
              .append(csv(e.getResourceId())).append(',')
              .append(csv(e.getEntityType())).append(',')
              .append(csv(e.getEntityId())).append(',')
              .append(csv(null)).append(',')
              .append(csv(e.getIpAddress())).append(',')
              .append(csv(e.getTraceId())).append(',')
              .append(csv(e.getRequestId())).append(',')
              .append(csv(e.getCreatedAt())).append('\n');
        }
        return sb.toString();
    }

    private static String csv(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    static String sign(String content) {
        try {
            Mac mac = Mac.getInstance(SIGNING_ALGORITHM);
            mac.init(new SecretKeySpec(DEFAULT_EXPORT_SIGNING_KEY.getBytes(StandardCharsets.UTF_8), SIGNING_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign export", e);
        }
    }
}