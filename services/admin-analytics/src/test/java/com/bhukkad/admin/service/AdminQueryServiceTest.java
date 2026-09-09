package com.bhukkad.admin.service;

import com.bhukkad.admin.domain.AuditEvent;
import com.bhukkad.admin.domain.AuditEventRepository;
import com.bhukkad.admin.domain.FraudEvent;
import com.bhukkad.admin.domain.FraudEventRepository;
import com.bhukkad.admin.domain.RestaurantOrderStat;
import com.bhukkad.admin.domain.RestaurantOrderStatRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminQueryServiceTest {

    @Mock private AuditEventRepository auditRepository;
    @Mock private FraudEventRepository fraudRepository;
    @Mock private RestaurantOrderStatRepository statRepository;
    @InjectMocks private AdminQueryService service;

    @Test
    void flagFraud_createsReviewEvent() {
        when(fraudRepository.save(any(FraudEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        FraudEvent event = service.flagFraud(42L, "velocity-check", "HIGH");

        assertThat(event.getStatus()).isEqualTo(FraudEvent.STATUS_REVIEW);
        assertThat(event.getCustomerId()).isEqualTo(42L);
        assertThat(event.getRule()).isEqualTo("velocity-check");
    }

    @Test
    void fraudAlerts_withStatus_filtersBoundedPage() {
        service.fraudAlerts("REVIEW");

        var captor = org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(fraudRepository).findByStatusOrderByCreatedAtDesc(any(), captor.capture());
        // PERF-3: bounded, newest-first (ORDER BY created_at DESC in SQL).
        assertThat(captor.getValue().getPageSize()).isEqualTo(200);
        assertThat(captor.getValue().getSort().getOrderFor("createdAt")).isNotNull();
    }

    @Test
    void fraudAlerts_nullStatus_returnsBoundedPage() {
        when(fraudRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());

        service.fraudAlerts(null);

        var captor = org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(fraudRepository).findAll(captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(200);
    }

    @Test
    void auditTrail_delegatesToRepository() {
        AuditEvent event = new AuditEvent();
        when(auditRepository.findByEntityTypeAndEntityId("ORDER", 5L)).thenReturn(List.of(event));

        assertThat(service.auditTrail("ORDER", 5L)).containsExactly(event);
    }

    @Test
    void restaurantStats_delegatesToBoundedPage() {
        RestaurantOrderStat stat = new RestaurantOrderStat();
        when(statRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(stat)));

        assertThat(service.restaurantStats()).containsExactly(stat);
        verify(statRepository, org.mockito.Mockito.never()).findAll();
    }
}
