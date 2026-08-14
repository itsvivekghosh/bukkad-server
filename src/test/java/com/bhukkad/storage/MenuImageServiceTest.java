package com.bhukkad.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MenuImageServiceTest {

    private ImageStorageProperties properties;
    private MenuImageService menuImageService;

    @BeforeEach
    void setUp() {
        properties = new ImageStorageProperties();
        properties.setKeyPrefix("menu-items");
        menuImageService = new MenuImageService(properties, null);
    }

    @Test
    void resolvePublicUrl_returnsLegacyHttpUrlsAsIs() {
        assertEquals("https://cdn.example.com/pic.jpg",
                menuImageService.resolvePublicUrl("https://cdn.example.com/pic.jpg"));
    }

    @Test
    void resolvePublicUrl_returnsStoredKeyWhenS3Disabled() {
        assertEquals("menu-items/1/2/uuid.jpg",
                menuImageService.resolvePublicUrl("menu-items/1/2/uuid.jpg"));
    }

    @Test
    void validateImageKey_rejectsInvalidPrefix() {
        assertThrows(com.bhukkad.exception.BusinessException.class,
                () -> menuImageService.validateImageKey("bad/key"));
    }

    @Test
    void generateImageKey_usesRestaurantAndMenuItemIds() {
        String key = menuImageService.generateImageKey(5L, 9L, "image/png");

        assertEquals(true, key.startsWith("menu-items/5/9/"));
        assertEquals(true, key.endsWith(".png"));
    }
}
