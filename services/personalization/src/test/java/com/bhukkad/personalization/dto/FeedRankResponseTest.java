package com.bhukkad.personalization.dto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FeedRankResponseTest {

    @Test
    void constructorAndGetters() {
        List<Long> ranked = List.of(5L, 3L, 1L, 2L);
        Map<Long, Double> scores = Map.of(
                5L, 10.0,
                3L, 8.0,
                1L, 5.0,
                2L, 2.0
        );

        FeedRankResponse response = new FeedRankResponse(ranked, scores);

        assertEquals(4, response.getRankedRestaurantIds().size());
        assertEquals(5L, response.getRankedRestaurantIds().get(0));
        assertEquals(3L, response.getRankedRestaurantIds().get(1));
        assertEquals(1L, response.getRankedRestaurantIds().get(2));
        assertEquals(2L, response.getRankedRestaurantIds().get(3));

        assertEquals(10.0, response.getAffinityScores().get(5L));
        assertEquals(8.0, response.getAffinityScores().get(3L));
        assertEquals(5.0, response.getAffinityScores().get(1L));
        assertEquals(2.0, response.getAffinityScores().get(2L));
    }

    @Test
    void emptyRankedList() {
        FeedRankResponse response = new FeedRankResponse(List.of(), Map.of());

        assertTrue(response.getRankedRestaurantIds().isEmpty());
        assertTrue(response.getAffinityScores().isEmpty());
    }

    @Test
    void setters() {
        FeedRankResponse response = new FeedRankResponse();
        List<Long> newRanked = List.of(10L, 20L);
        Map<Long, Double> newScores = Map.of(10L, 15.0, 20L, 10.0);

        response.setRankedRestaurantIds(newRanked);
        response.setAffinityScores(newScores);

        assertEquals(2, response.getRankedRestaurantIds().size());
        assertEquals(10L, response.getRankedRestaurantIds().get(0));
    }
}
