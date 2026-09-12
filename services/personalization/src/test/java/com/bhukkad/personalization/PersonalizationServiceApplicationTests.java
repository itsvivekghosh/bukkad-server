package com.bhukkad.personalization;

import com.bhukkad.personalization.api.dto.response.RecommendationResponse;
import com.bhukkad.personalization.api.dto.response.FeedRankResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PersonalizationServiceApplicationTests {

    @Test
    void recommendationResponseBuilderWorks() {
        RecommendationResponse response = RecommendationResponse.builder()
                .itemId(1L)
                .name("Test Item")
                .price(99.99)
                .restaurantId(5L)
                .build();

        assertEquals(1L, response.getItemId());
        assertEquals("Test Item", response.getName());
        assertEquals(99.99, response.getPrice());
    }

    @Test
    void feedRankResponseWorks() {
        List<Long> ranked = List.of(5L, 3L, 1L);
        Map<Long, Double> scores = Map.of(5L, 10.0, 3L, 5.0, 1L, 1.0);
        FeedRankResponse response = new FeedRankResponse(ranked, scores);

        assertEquals(3, response.getRankedRestaurantIds().size());
        assertEquals(5L, response.getRankedRestaurantIds().get(0));
        assertEquals(10.0, response.getAffinityScores().get(5L));
    }
}
