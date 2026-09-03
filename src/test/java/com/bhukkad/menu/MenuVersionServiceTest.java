package com.bhukkad.menu;

import com.bhukkad.dto.response.MenuVersionResponse;
import com.bhukkad.entity.MenuCategory;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.entity.MenuVersion;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RestaurantOwner;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.repository.MenuCategoryRepository;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.MenuVersionRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MenuVersionServiceTest {

    @Mock
    private MenuVersionRepository menuVersionRepository;

    @Mock
    private MenuCategoryRepository menuCategoryRepository;

    @Mock
    private MenuItemRepository menuItemRepository;

    @Mock
    private RestaurantRepository restaurantRepository;

    @org.mockito.Spy
    private final ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private MenuVersionService service;

    private Restaurant ownedRestaurant(Long ownerId) {
        RestaurantOwner owner = new RestaurantOwner();
        owner.setId(ownerId);
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);
        restaurant.setOwner(owner);
        return restaurant;
    }

    private MenuVersion version(long id, long restaurantId, int versionNumber) {
        MenuVersion version = new MenuVersion();
        version.setId(id);
        version.setRestaurantId(restaurantId);
        version.setVersionNumber(versionNumber);
        version.setStatus(MenuVersion.MenuVersionStatus.DRAFT);
        return version;
    }

    @Test
    void createDraft_serializesSnapshot() throws Exception {
        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ownedRestaurant(100L)));

        MenuCategory category = new MenuCategory();
        category.setId(10L);
        category.setName("Starters");
        category.setDisplayOrder(1);
        category.setActive(true);
        when(menuCategoryRepository.findByRestaurantIdWithRestaurantOrderByDisplayOrderAsc(1L))
                .thenReturn(List.of(category));

        MenuItem item = new MenuItem();
        item.setId(20L);
        item.setName("Paneer Tikka");
        item.setPrice(250.0);
        item.setAvailable(true);
        item.setIsVeg(true);
        item.setCategory(category);
        when(menuItemRepository.findByRestaurantIdWithDetails(1L)).thenReturn(List.of(item));

        when(menuVersionRepository.findTopByRestaurantIdOrderByVersionNumberDesc(1L))
                .thenReturn(Optional.empty());

        MenuVersionResponse response = service.createDraft(1L, 100L, "Draft v1");

        ArgumentCaptor<MenuVersion> captor = ArgumentCaptor.forClass(MenuVersion.class);
        verify(menuVersionRepository).save(captor.capture());
        MenuVersion saved = captor.getValue();

        assertEquals(1, saved.getVersionNumber());
        assertEquals(MenuVersion.MenuVersionStatus.DRAFT, saved.getStatus());
        assertNull(saved.getPublishedAt());
        assertNotNull(saved.getCreatedAt());
        assertTrue(saved.getSnapshotJson().contains("\"categories\""));
        assertTrue(saved.getSnapshotJson().contains("\"items\""));
        assertTrue(saved.getSnapshotJson().contains("Paneer Tikka"));
        assertTrue(saved.getSnapshotJson().contains("\"categoryId\":10"));

        assertEquals(1L, response.restaurantId());
        assertEquals("DRAFT", response.status());
    }

    @Test
    void preview_returnsParsedSnapshot() {
        MenuVersion version = version(5L, 1L, 2);
        version.setSnapshotJson("{\"categories\":[{\"id\":10,\"name\":\"Starters\"}]," +
                "\"items\":[{\"id\":20,\"name\":\"Paneer Tikka\",\"price\":250.0}]}");
        when(menuVersionRepository.findById(5L)).thenReturn(Optional.of(version));
        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ownedRestaurant(100L)));

        Map<String, Object> snapshot = service.preview(5L, 100L);

        assertNotNull(snapshot);
        List<?> items = (List<?>) snapshot.get("items");
        assertEquals(1, items.size());
        Map<?, ?> item = (Map<?, ?>) items.get(0);
        assertEquals("Paneer Tikka", item.get("name"));
        assertEquals(250.0, item.get("price"));
    }

    @Test
    void publish_flipsStatusToPublished() {
        MenuVersion version = version(5L, 1L, 2);
        when(menuVersionRepository.findById(5L)).thenReturn(Optional.of(version));
        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ownedRestaurant(100L)));

        MenuVersionResponse response = service.publish(5L, 100L);

        ArgumentCaptor<MenuVersion> captor = ArgumentCaptor.forClass(MenuVersion.class);
        verify(menuVersionRepository).save(captor.capture());
        MenuVersion saved = captor.getValue();

        assertEquals(MenuVersion.MenuVersionStatus.PUBLISHED, saved.getStatus());
        assertNotNull(saved.getPublishedAt());
        assertEquals("PUBLISHED", response.status());
        assertNotNull(response.publishedAt());
    }

    @Test
    void createDraft_deniedForOtherOwner() {
        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ownedRestaurant(100L)));

        assertThrows(BusinessException.class, () -> service.createDraft(1L, 999L, "x"));

        verify(menuVersionRepository, never()).save(any());
    }

    @Test
    void publish_deniedForOtherOwner() {
        MenuVersion version = version(5L, 1L, 2);
        when(menuVersionRepository.findById(5L)).thenReturn(Optional.of(version));
        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ownedRestaurant(100L)));

        assertThrows(BusinessException.class, () -> service.publish(5L, 999L));

        verify(menuVersionRepository, never()).save(any());
    }

    @Test
    void latestVersion_returnsTopVersion() {
        MenuVersion version = version(9L, 1L, 3);
        when(menuVersionRepository.findTopByRestaurantIdOrderByVersionNumberDesc(1L))
                .thenReturn(Optional.of(version));

        Optional<MenuVersion> latest = service.latestVersion(1L);

        assertTrue(latest.isPresent());
        assertEquals(3, latest.get().getVersionNumber());
    }

    @Test
    void latestVersion_emptyWhenNoVersionsExist() {
        when(menuVersionRepository.findTopByRestaurantIdOrderByVersionNumberDesc(1L))
                .thenReturn(Optional.empty());

        assertTrue(service.latestVersion(1L).isEmpty());
    }
}