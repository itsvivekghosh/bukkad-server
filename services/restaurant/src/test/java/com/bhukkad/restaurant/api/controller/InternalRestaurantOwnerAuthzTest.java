package com.bhukkad.restaurant.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Machine-only surface contract (audit HIGH-IDOR-3): the restaurant ownership
 * oracle is a service-to-service endpoint (consumed by order's owner gate) and
 * must never be reachable with an ordinary user JWT — mesh token (ROLE_SERVICE)
 * or admin only, per the InternalMenuController convention.
 */
class InternalRestaurantOwnerAuthzTest {

    @Test
    void internalRestaurantController_isServiceOrAdminGated() {
        PreAuthorize gate = InternalRestaurantController.class.getAnnotation(PreAuthorize.class);
        assertThat(gate).isNotNull();
        assertThat(gate.value()).contains("SERVICE").contains("ADMIN");
    }

    @Test
    void ownerEndpoint_isMappedToInternalPath() throws Exception {
        Method m = findMethod("owner");
        GetMapping mapping = m.getAnnotation(GetMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(String.join("", mapping.value())).isEqualTo("/{restaurantId}/owner");
    }

    @Test
    void controllerIsBoundToInternalPrefix() {
        RequestMapping mapping = InternalRestaurantController.class.getAnnotation(RequestMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(String.join("", mapping.value())).isEqualTo("/api/v1/internal/restaurants");
    }

    private static Method findMethod(String name) throws Exception {
        for (Method m : InternalRestaurantController.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new AssertionError("missing method " + name);
    }
}
