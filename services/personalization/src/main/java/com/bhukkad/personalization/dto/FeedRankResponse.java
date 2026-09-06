package com.bhukkad.personalization.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeedRankResponse {
    private List<Long> rankedRestaurantIds;
    private Map<Long, Double> affinityScores;
}
