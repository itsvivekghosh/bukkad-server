package com.bhukkad.admin.analytics.api.controller;

import com.bhukkad.admin.analytics.domain.entity.AuditEvent;
import com.bhukkad.admin.analytics.domain.repository.AuditEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComplianceControllerTest {

    @Mock
    private AuditEventRepository auditEventRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @InjectMocks
    private ComplianceController controller;

    @Test
    void searchAuditLogs_delegatesToRepository() {
        AuditEvent event = new AuditEvent();
        event.setId(1L);
        event.setAction("CREATE");
        event.setResourceType("ORDER");
        event.setResourceId("100");
        event.setActorId(7L);
        event.setCreatedAt(LocalDateTime.now());
        when(auditEventRepository.findAll(any(Specification.class))).thenReturn(List.of(event));

        List<AuditEvent> result = controller.searchAuditLogs(7L, "ORDER", "100", "CREATE", null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAction()).isEqualTo("CREATE");
    }

    @Test
    void exportAuditLogs_jsonReturnsSignedResponse() throws Exception {
        AuditEvent event = new AuditEvent();
        event.setId(1L);
        event.setAction("EXPORT");
        event.setResourceType("REPORT");
        event.setResourceId("r1");
        event.setCreatedAt(LocalDateTime.now());
        when(auditEventRepository.findAll(any(Specification.class))).thenReturn(List.of(event));

        ResponseEntity<String> response = controller.exportAuditLogs(null, null, null, null, null, null, "json");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getFirst("X-Export-Signature")).isNotBlank();
        assertThat(response.getHeaders().getFirst("X-Export-Signature-Algorithm")).isEqualTo("HmacSHA256");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("audit-logs.json");
        assertThat(response.getBody()).contains("EXPORT");
        // signature must match body
        assertThat(response.getHeaders().getFirst("X-Export-Signature"))
                .isEqualTo(ComplianceController.sign(response.getBody()));
    }

    @Test
    void exportAuditLogs_csvReturnsSignedCsv() throws Exception {
        AuditEvent event = new AuditEvent();
        event.setId(2L);
        event.setAction("DELETE");
        event.setResourceType("USER");
        event.setResourceId("55");
        event.setCreatedAt(LocalDateTime.now());
        when(auditEventRepository.findAll(any(Specification.class))).thenReturn(List.of(event));

        ResponseEntity<String> response = controller.exportAuditLogs(null, "USER", null, null, null, null, "csv");

        assertThat(response.getHeaders().getFirst("Content-Type")).isEqualTo("text/csv");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("audit-logs.csv");
        assertThat(response.getBody()).startsWith("id,actorId,actorType,action,resourceType,resourceId");
        assertThat(response.getHeaders().getFirst("X-Export-Signature"))
                .isEqualTo(ComplianceController.sign(response.getBody()));
    }
}