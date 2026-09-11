package com.bhukkad.delivery.api;

import com.bhukkad.delivery.domain.DeliveryAssignment;
import com.bhukkad.delivery.service.DeliveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit HIGH-IDOR-2: assign/delivered are mesh-driven mutations behind a
 * security chain that only requires "authenticated" — method security must
 * restrict them to SERVICE (mesh token) or ADMIN, or any user JWT could drive
 * the delivery lifecycle directly. The gate is asserted on the annotation (the
 * mesh principal is a String subject, so a principal-based unit assertion
 * cannot express the SERVICE case; see InternalAndInventoryAuthzTest for the
 * established contract-test pattern).
 */
@ExtendWith(MockitoExtension.class)
class DeliveryControllerGuardTest {

    @Mock private DeliveryService deliveryService;
    @InjectMocks private DeliveryController controller;

    @Test
    void assign_isServiceOrAdminGated() throws Exception {
        PreAuthorize gate = findMethod("assign").getAnnotation(PreAuthorize.class);
        assertThat(gate).as("assign must carry @PreAuthorize").isNotNull();
        assertThat(gate.value()).contains("SERVICE").contains("ADMIN");
    }

    @Test
    void markDelivered_isServiceOrAdminGated() throws Exception {
        PreAuthorize gate = findMethod("markDelivered").getAnnotation(PreAuthorize.class);
        assertThat(gate).as("markDelivered must carry @PreAuthorize").isNotNull();
        assertThat(gate.value()).contains("SERVICE").contains("ADMIN");
    }

    @Test
    void assign_delegatesToService() {
        DeliveryAssignment assignment = new DeliveryAssignment();
        when(deliveryService.assign(10L)).thenReturn(assignment);

        assertThat(controller.assign(10L)).isSameAs(assignment);
        verify(deliveryService).assign(10L);
    }

    @Test
    void markDelivered_delegatesToService() {
        DeliveryAssignment assignment = new DeliveryAssignment();
        when(deliveryService.markDelivered(10L)).thenReturn(assignment);

        assertThat(controller.markDelivered(10L)).isSameAs(assignment);
        verify(deliveryService).markDelivered(10L);
    }

    private static Method findMethod(String name) throws Exception {
        for (Method m : DeliveryController.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new AssertionError("missing method " + name);
    }
}
