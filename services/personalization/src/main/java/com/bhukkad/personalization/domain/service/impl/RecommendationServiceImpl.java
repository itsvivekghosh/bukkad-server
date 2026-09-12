package com.bhukkad.personalization.domain.service.impl;

import com.bhukkad.personalization.config.RecommendationProperties;
import com.bhukkad.personalization.api.dto.response.FeedRankResponse;
import com.bhukkad.personalization.api.dto.response.RecommendationResponse;
import com.bhukkad.personalization.domain.service.RecommendationQueryService;
import com.bhukkad.personalization.domain.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationServiceImpl implements RecommendationService {

    private static final int BREAKFAST_END = 11;
    private static final int LUNCH_END = 16;
    private static final int SNACKS_END = 19;

    private static final Set<String> BREAKFAST_TERMS = Set.of("breakfast", "idli", "dosa", "poha", "paratha", "omelette", "cereal");
    private static final Set<String> LUNCH_TERMS = Set.of("lunch", "thali", "biryani", "rice", "combo");
    private static final Set<String> SNACKS_TERMS = Set.of("snack", "samosa", "chaat", "fries", "rolls");
    private static final Set<String> DINNER_TERMS = Set.of("dinner", "curry", "roti", "naan", "grill", "combo");

    private final RecommendationQueryService queryService;
    private final RecommendationProperties properties;

    @Override
    public List<RecommendationResponse> reorderSuggestions(Long customerId) {
        List<Object[]> rows = queryService.findCustomerItemFrequencies(customerId, properties.getMaxItems());
        return rows.stream()
                .map(row -> RecommendationResponse.builder()
                        .itemId(((Number) row[0]).longValue())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public List<RecommendationResponse> collaborativeSuggestions(Long customerId) {
        List<Object[]> ownItems = queryService.findCustomerItemFrequencies(customerId, properties.getCoOrderedItemLimit());
        if (ownItems.isEmpty()) {
            return List.of();
        }

        List<Long> ownItemIds = ownItems.stream()
                .map(row -> ((Number) row[0]).longValue())
                .collect(Collectors.toList());

        Set<Long> orderedAlready = new HashSet<>(ownItemIds);
        List<RecommendationResponse> candidates = new ArrayList<>();

        List<Object[]> coOrdered = queryService.findCoOrderedItems(ownItemIds, customerId, properties.getMaxItems() * 3);
        for (Object[] row : coOrdered) {
            long itemId = ((Number) row[0]).longValue();
            if (orderedAlready.add(itemId)) {
                candidates.add(RecommendationResponse.builder().itemId(itemId).build());
            }
            if (candidates.size() >= properties.getMaxItems()) {
                break;
            }
        }
        return candidates;
    }

    @Override
    public List<RecommendationResponse> timeAwareSuggestions(Long customerId) {
        MealWindow window = MealWindow.current();
        List<RecommendationResponse> suggestions = reorderSuggestions(customerId);
        // In a real implementation, would filter by meal window terms
        return suggestions;
    }

    @Override
    public FeedRankResponse rankRestaurantsForCustomer(Long customerId, List<Long> restaurantIds) {
        Map<Long, Long> affinities = new LinkedHashMap<>();
        List<Object[]> rows = queryService.findCustomerRestaurantAffinities(customerId, properties.getCustomerAffinityLimit());
        for (Object[] row : rows) {
            affinities.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }

        Map<Long, Long> position = new LinkedHashMap<>();
        for (int i = 0; i < restaurantIds.size(); i++) {
            position.put(restaurantIds.get(i), (long) i);
        }

        List<Long> ranked = new ArrayList<>(restaurantIds);
        ranked.sort((a, b) -> {
            long scoreA = affinities.getOrDefault(a, 0L);
            long scoreB = affinities.getOrDefault(b, 0L);
            if (scoreA != scoreB) {
                return Long.compare(scoreB, scoreA);
            }
            return Long.compare(position.get(a), position.get(b));
        });

        Map<Long, Double> scores = new LinkedHashMap<>();
        for (Long id : ranked) {
            scores.put(id, affinities.getOrDefault(id, 0L) * 1.0);
        }

        return new FeedRankResponse(ranked, scores);
    }

    enum MealWindow {
        BREAKFAST(BREAKFAST_TERMS),
        LUNCH(LUNCH_TERMS),
        SNACKS(SNACKS_TERMS),
        DINNER(DINNER_TERMS);

        final Set<String> terms;

        MealWindow(Set<String> terms) {
            this.terms = terms;
        }

        static MealWindow current() {
            return currentAt(LocalTime.now());
        }

        static MealWindow currentAt(LocalTime time) {
            int hour = time.getHour();
            if (hour < BREAKFAST_END) return BREAKFAST;
            if (hour < LUNCH_END) return LUNCH;
            if (hour < SNACKS_END) return SNACKS;
            return DINNER;
        }
    }
}
