package com.bhukkad.personalization.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecommendationPropertiesTest {

    @Test
    void defaultValues() {
        RecommendationProperties props = new RecommendationProperties();

        assertEquals(10, props.getMaxItems());
        assertEquals(50, props.getCoOrderedItemLimit());
        assertEquals(25, props.getCustomerAffinityLimit());
        assertEquals(3, props.getTopRestaurantsLimit());
    }

    @Test
    void customValues() {
        RecommendationProperties props = new RecommendationProperties();
        props.setMaxItems(20);
        props.setCoOrderedItemLimit(100);
        props.setCustomerAffinityLimit(50);
        props.setTopRestaurantsLimit(5);

        assertEquals(20, props.getMaxItems());
        assertEquals(100, props.getCoOrderedItemLimit());
        assertEquals(50, props.getCustomerAffinityLimit());
        assertEquals(5, props.getTopRestaurantsLimit());
    }
}
