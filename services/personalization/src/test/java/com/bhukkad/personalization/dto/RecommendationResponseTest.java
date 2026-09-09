package com.bhukkad.personalization.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecommendationResponseTest {

    @Test
    void builderSetsAllFields() {
        RecommendationResponse response = RecommendationResponse.builder()
                .itemId(1L)
                .name("Margherita Pizza")
                .description("Classic Italian pizza")
                .price(299.99)
                .restaurantId(5L)
                .restaurantName("Pizza Palace")
                .category("Pizza")
                .rating(4.5)
                .imageUrl("https://example.com/pizza.jpg")
                .build();

        assertEquals(1L, response.getItemId());
        assertEquals("Margherita Pizza", response.getName());
        assertEquals("Classic Italian pizza", response.getDescription());
        assertEquals(299.99, response.getPrice());
        assertEquals(5L, response.getRestaurantId());
        assertEquals("Pizza Palace", response.getRestaurantName());
        assertEquals("Pizza", response.getCategory());
        assertEquals(4.5, response.getRating());
        assertEquals("https://example.com/pizza.jpg", response.getImageUrl());
    }

    @Test
    void noArgsConstructor() {
        RecommendationResponse response = new RecommendationResponse();

        assertNull(response.getItemId());
        assertNull(response.getName());
        assertNull(response.getPrice());
    }

    @Test
    void allArgsConstructor() {
        RecommendationResponse response = new RecommendationResponse(
                1L, "Test Item", "Description", 99.99,
                5L, "Restaurant", "Category", 4.0, "http://image.url"
        );

        assertEquals(1L, response.getItemId());
        assertEquals("Test Item", response.getName());
        assertEquals("Description", response.getDescription());
        assertEquals(99.99, response.getPrice());
        assertEquals(5L, response.getRestaurantId());
        assertEquals("Restaurant", response.getRestaurantName());
        assertEquals("Category", response.getCategory());
        assertEquals(4.0, response.getRating());
        assertEquals("http://image.url", response.getImageUrl());
    }

    @Test
    void setters() {
        RecommendationResponse response = new RecommendationResponse();
        response.setItemId(10L);
        response.setName("Updated Item");
        response.setPrice(199.99);

        assertEquals(10L, response.getItemId());
        assertEquals("Updated Item", response.getName());
        assertEquals(199.99, response.getPrice());
    }
}
