package com.bhukkad.admin.api;
import com.bhukkad.admin.api.controller.AdminController;

import com.bhukkad.admin.domain.entity.AuditEvent;
import com.bhukkad.admin.domain.entity.FraudEvent;
import com.bhukkad.admin.domain.entity.RestaurantOrderStat;
import com.bhukkad.admin.domain.service.AdminQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock private AdminQueryService queryService;
    @InjectMocks private AdminController controller;

    @Test
    void audit_delegatesToQueryService() {
        when(queryService.auditTrail("ORDER", 5L)).thenReturn(List.of(new AuditEvent()));

        assertThat(controller.audit("ORDER", 5L)).hasSize(1);
    }

    @Test
    void fraud_delegatesWithStatus() {
        when(queryService.fraudAlerts("REVIEW")).thenReturn(List.of(new FraudEvent()));

        assertThat(controller.fraud("REVIEW")).hasSize(1);
    }

    @Test
    void flagFraud_delegatesWithSeverity() {
        FraudEvent event = new FraudEvent();
        when(queryService.flagFraud(42L, "velocity", "HIGH")).thenReturn(event);

        assertThat(controller.flagFraud(42L, "velocity", "HIGH")).isSameAs(event);
        verify(queryService).flagFraud(42L, "velocity", "HIGH");
    }

    @Test
    void restaurantStats_delegatesToQueryService() {
        when(queryService.restaurantStats()).thenReturn(List.of(new RestaurantOrderStat()));

        assertThat(controller.restaurantStats()).hasSize(1);
    }
}
