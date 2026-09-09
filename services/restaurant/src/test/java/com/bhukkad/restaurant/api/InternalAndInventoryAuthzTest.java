package com.bhukkad.restaurant.api;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Machine-only surface contract (audit batch C): menu snapshot and
 * reservation mutation must never be reachable with an ordinary user JWT.
 */
class InternalAndInventoryAuthzTest {

    @Test
    void internalMenuController_isServiceOrAdminGated() {
        PreAuthorize gate = InternalMenuController.class.getAnnotation(PreAuthorize.class);
        assertThat(gate).isNotNull();
        assertThat(gate.value()).contains("SERVICE");
    }

    @Test
    void inventoryReservation_endpointsAreServiceGated() throws Exception {
        for (String name : new String[] {"reserveStock", "releaseStock"}) {
            Method m = findMethod(name);
            PreAuthorize gate = m.getAnnotation(PreAuthorize.class);
            assertThat(gate).as(name + " must carry @PreAuthorize").isNotNull();
            assertThat(gate.value()).contains("SERVICE");
        }
        PreAuthorize sync = findMethod("syncStock").getAnnotation(PreAuthorize.class);
        assertThat(sync).isNotNull();
        assertThat(sync.value()).contains("SERVICE");
    }

    private static Method findMethod(String name) {
        for (Method m : InventoryStockReservationController.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new AssertionError("missing method " + name);
    }
}
