package com.bhukkad.order.api.controller;

import com.bhukkad.order.domain.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Internal ownership oracle consumed by realtime (stream authz) and
 * survey/dispute flows. Unit matrix: contract shape, method mapping,
 * delegation, and the SERVICE/ADMIN gate annotation contract (enforced
 * end-to-end by ServiceJwtAuthFilter + method security in @SpringBootTest
 * coverage elsewhere).
 */
@ExtendWith(MockitoExtension.class)
class InternalOrderControllerTest {

    @Mock private OrderService orderService;
    @InjectMocks private InternalOrderController controller;

    @Test
    void customer_returnsOwnershipRef() {
        OrderResponse order = new OrderResponse(77L, 5L, 9L, "CREATED", new java.math.BigDecimal("20.00"), java.util.List.of());
        when(orderService.getOrder(77L)).thenReturn(order);

        InternalOrderController.CustomerRef ref = controller.customer(77L);

        assertThat(ref.customerId()).isEqualTo(5L);
    }

    @Test
    void declaresInternalServiceContract() throws Exception {
        var m = InternalOrderController.class.getMethod("customer", Long.class);
        PreAuthorize gate = m.getAnnotation(PreAuthorize.class);
        assertThat(gate).isNotNull();
        assertThat(gate.value()).contains("SERVICE");

        var mapping = InternalOrderController.class.getAnnotation(RequestMapping.class);
        assertThat(mapping.value()[0]).isEqualTo("/api/v1/internal/orders");
    }
}
