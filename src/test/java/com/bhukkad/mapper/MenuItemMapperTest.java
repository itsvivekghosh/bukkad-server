package com.bhukkad.mapper;

import com.bhukkad.dto.response.MenuItemResponse;
import com.bhukkad.entity.MenuCategory;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.storage.MenuImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MenuItemMapperTest {

    @Mock
    private MenuImageService menuImageService;

    private MenuItemMapperImpl mapper;

    @BeforeEach
    void setUp() {
        mapper = new MenuItemMapperImpl();
        ReflectionTestUtils.setField(mapper, "menuImageService", menuImageService);
    }

    private MenuItem item(String name, MenuItem.FoodType foodType, MenuItem.SpiceLevel spiceLevel) {
        MenuItem item = new MenuItem();
        item.setId(1L);
        item.setName(name);
        item.setDescription("Description");
        item.setPrice(249.0);
        item.setFoodType(foodType);
        item.setIsVeg(true);
        item.setSpiceLevel(spiceLevel);
        item.setAvailable(true);
        item.setPreparationTime(15);
        item.setBestseller(true);
        item.setCalories(450);
        item.setServingSize("Medium");
        item.setImageUrl("images/paneer.jpg");
        item.setAdditionalImages(List.of("img1.jpg", "img2.jpg"));
        MenuCategory category = new MenuCategory();
        category.setName("Starters");
        item.setCategory(category);
        return item;
    }

    @Test void toResponse_mapsAllFields() {
        when(menuImageService.resolvePublicUrl("images/paneer.jpg")).thenReturn("https://cdn/img.jpg");
        when(menuImageService.resolvePublicUrl("img1.jpg")).thenReturn("https://cdn/img1.jpg");
        when(menuImageService.resolvePublicUrl("img2.jpg")).thenReturn("https://cdn/img2.jpg");

        MenuItemResponse response = mapper.resolveImageUrls(
                item("Paneer Tikka", MenuItem.FoodType.VEG, MenuItem.SpiceLevel.MEDIUM),
                mapper.toResponse(item("Paneer Tikka", MenuItem.FoodType.VEG, MenuItem.SpiceLevel.MEDIUM)));

        assertEquals("Paneer Tikka", response.getName());
        assertEquals("Starters", response.getCategoryName());
        assertEquals("VEG", response.getFoodType());
        assertEquals("MEDIUM", response.getSpiceLevel());
        assertEquals(true, response.getAvailable());
        assertEquals(true, response.getIsVeg());
        assertEquals(true, response.getBestseller());
        assertEquals(249.0, response.getPrice());
        assertEquals(15, response.getPreparationTime());
        assertEquals(450, response.getCalories());
        assertEquals("https://cdn/img.jpg", response.getImageUrl());
        assertEquals(List.of("https://cdn/img1.jpg", "https://cdn/img2.jpg"), response.getAdditionalImages());
    }

    @Test void toResponse_nullFoodTypeAndSpice() {
        when(menuImageService.resolvePublicUrl("images/paneer.jpg")).thenReturn("https://cdn/img.jpg");

        MenuItem item = item("Paneer", null, null);
        MenuItemResponse response = mapper.resolveImageUrls(item, mapper.toResponse(item));

        assertNull(response.getFoodType());
        assertNull(response.getSpiceLevel());
    }

    @Test void toResponse_nullAdditionalImages() {
        when(menuImageService.resolvePublicUrl("images/paneer.jpg")).thenReturn("https://cdn/img.jpg");

        MenuItem item = item("Paneer", MenuItem.FoodType.VEG, MenuItem.SpiceLevel.MILD);
        item.setAdditionalImages(null);
        MenuItemResponse response = mapper.resolveImageUrls(item, mapper.toResponse(item));

        assertNull(response.getAdditionalImages());
    }

    @Test void toResponse_nullImageUrl() {
        when(menuImageService.resolvePublicUrl(null)).thenReturn(null);

        MenuItem item = item("Paneer", MenuItem.FoodType.VEG, MenuItem.SpiceLevel.MILD);
        item.setImageUrl(null);
        MenuItemResponse response = mapper.resolveImageUrls(item, mapper.toResponse(item));

        assertNull(response.getImageUrl());
    }
}