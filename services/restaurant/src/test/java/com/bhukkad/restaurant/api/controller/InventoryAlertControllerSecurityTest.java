package com.bhukkad.restaurant.api.controller;

import com.bhukkad.restaurant.domain.entity.InventoryAlert;
import com.bhukkad.restaurant.domain.service.impl.InventoryAlertService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Security annotation audit for InventoryAlertController.
 *
 * <p>Raising and reading inventory alerts must be restricted to restaurant
 * owners or admins — unauthenticated callers must not be able to pollute or
 * scrape inventory state.</p>
 */
@ExtendWith(MockitoExtension.class)
class InventoryAlertControllerSecurityTest {

    @Mock private InventoryAlertService alertService;
    @InjectMocks private InventoryAlertController controller;

    @Test
    void raise_isOwnerOrAdminGated() throws Exception {
        PreAuthorize gate = findMethod(controller.getClass(), "raise",
                Long.class, int.class, int.class)
                .getAnnotation(PreAuthorize.class);
        assertThat(gate).as("raise must carry @PreAuthorize").isNotNull();
        assertThat(gate.value()).contains("RESTAURANT_OWNER").contains("ADMIN");
    }

    @Test
    void recent_isOwnerOrAdminGated() throws Exception {
        PreAuthorize gate = findMethod(controller.getClass(), "recent", Long.class)
                .getAnnotation(PreAuthorize.class);
        assertThat(gate).as("recent must carry @PreAuthorize").isNotNull();
        assertThat(gate.value()).contains("RESTAURANT_OWNER").contains("ADMIN");
    }

    @Test
    void raise_delegatesToService() {
        when(alertService.raise(42L, 5, 10))
                .thenAnswer(inv -> {
                    InventoryAlert a = new InventoryAlert();
                    a.setMenuItemId(42L);
                    return a;
                });

        InventoryAlert alert = controller.raise(42L, 5, 10);

        assertThat(alert.getMenuItemId()).isEqualTo(42L);
    }

    @Test
    void recent_delegatesToService() {
        when(alertService.recent(42L)).thenReturn(List.of());

        assertThat(controller.recent(42L)).isEmpty();
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) throws Exception {
        try {
            return clazz.getDeclaredMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    return m;
                }
            }
            throw new AssertionError("missing method " + name + " in " + clazz.getName());
        }
    }
}
