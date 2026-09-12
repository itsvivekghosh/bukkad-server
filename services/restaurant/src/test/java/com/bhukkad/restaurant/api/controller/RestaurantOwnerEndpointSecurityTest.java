package com.bhukkad.restaurant.api.controller;

import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.restaurant.domain.entity.MenuCategory;
import com.bhukkad.restaurant.domain.entity.Restaurant;
import com.bhukkad.restaurant.domain.repository.RestaurantRepository;
import com.bhukkad.restaurant.domain.service.impl.MenuCategoryService;
import com.bhukkad.restaurant.domain.service.impl.RestaurantBusyService;
import com.bhukkad.restaurant.domain.service.impl.RestaurantDashboardService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Per-endpoint unit matrix for restaurant owner surfaces: busy-mode and menu
 * categories are owner-or-admin (the path id was previously trusted blindly).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RestaurantOwnerEndpointSecurityTest {

    private static TokenPrincipal principal(long userId, String scope) {
        return new TokenPrincipal(userId, "u@t.test", scope);
    }

    @Mock private RestaurantRepository restaurantRepository;
    @Mock private RestaurantBusyService busyService;
    @Mock private RestaurantDashboardService dashboardService;
    @Mock private MenuCategoryService menuCategoryService;
    @Mock private com.bhukkad.restaurant.domain.service.impl.RestaurantQueryService queryService;
    @Mock private com.bhukkad.restaurant.domain.service.impl.RestaurantAdminService adminService;

    private RestaurantOwnerController ownerGuard;
    private RestaurantController restaurantController;
    private MenuCategoryController categoryController;

    @org.junit.jupiter.api.BeforeEach
    void wire() {
        ownerGuard = new RestaurantOwnerController(restaurantRepository,
                org.mockito.Mockito.mock(com.bhukkad.restaurant.domain.repository.ReviewRepository.class));
        restaurantController = new RestaurantController(queryService, adminService, busyService,
                dashboardService, ownerGuard);
        categoryController = new MenuCategoryController(menuCategoryService, ownerGuard);
    }

    private Restaurant restaurant(long id, Long ownerId) {
        Restaurant r = new Restaurant();
        r.setId(id);
        r.setOwnerId(ownerId);
        return r;
    }

    @Test
    void busyMode_owner_allowed() {
        when(restaurantRepository.findById(5L)).thenReturn(Optional.of(restaurant(5L, 7L)));

        var response = restaurantController.enableBusyMode(principal(7L, "RESTAURANT_OWNER"),
                5L, new com.bhukkad.restaurant.api.dto.request.RestaurantBusyModeRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void busyMode_crossOwner_throws() {
        when(restaurantRepository.findById(5L)).thenReturn(Optional.of(restaurant(5L, 7L)));

        assertThatThrownBy(() -> restaurantController.enableBusyMode(principal(8L, "RESTAURANT_OWNER"),
                5L, new com.bhukkad.restaurant.api.dto.request.RestaurantBusyModeRequest()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void busyMode_customer_throws() {
        when(restaurantRepository.findById(5L)).thenReturn(Optional.of(restaurant(5L, 7L)));

        assertThatThrownBy(() -> restaurantController.disableBusyMode(principal(1L, "CUSTOMER"), 5L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void busyMode_admin_allowed() {
        when(restaurantRepository.findById(5L)).thenReturn(Optional.of(restaurant(5L, 7L)));

        assertThat(restaurantController.disableBusyMode(principal(99L, "ADMIN"), 5L)
                .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void dashboard_owner_only() {
        when(restaurantRepository.findById(5L)).thenReturn(Optional.of(restaurant(5L, 7L)));

        assertThatThrownBy(() -> restaurantController.getDashboard(principal(1L, "CUSTOMER"), 5L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void categoryCreate_crossOwner_throws() {
        when(restaurantRepository.findById(5L)).thenReturn(Optional.of(restaurant(5L, 7L)));

        assertThatThrownBy(() -> categoryController.create(principal(8L, "RESTAURANT_OWNER"),
                5L, new MenuCategory()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void categoryUpdate_owner_allowed() {
        MenuCategory existing = new MenuCategory();
        existing.setId(3L);
        existing.setRestaurantId(5L);
        when(menuCategoryService.get(3L)).thenReturn(existing);
        when(menuCategoryService.update(org.mockito.ArgumentMatchers.eq(3L), any()))
                .thenReturn(existing);
        when(restaurantRepository.findById(5L)).thenReturn(Optional.of(restaurant(5L, 7L)));

        var response = categoryController.update(principal(7L, "RESTAURANT_OWNER"), 3L,
                new MenuCategory());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }
}
