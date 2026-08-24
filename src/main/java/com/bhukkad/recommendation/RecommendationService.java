package com.bhukkad.recommendation;

import com.bhukkad.entity.MenuItem;
import com.bhukkad.repository.MenuItemRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI-powered personalization engine (FEATURE #4).
 *
 * <p>Combines four signals into customer-facing suggestions:</p>
 * <ul>
 *   <li><strong>Collaborative filtering</strong> — "customers who ordered X also
 *       ordered Y", computed from co-occurrence in delivered orders.</li>
 *   <li><strong>Affinity</strong> — the customer's own order frequency and the
 *       restaurants they return to.</li>
 *   <li><strong>Time-awareness</strong> — breakfast items in the morning, dinner
 *       combos in the evening, based on item tags and category names.</li>
 *   <li><strong>Reorder</strong> — frequently ordered items surfaced for one-tap
 *       re-purchase.</li>
 * </ul>
 *
 * <p>All analytical work happens in {@link RecommendationQueryService}; this class
 * owns the scoring, windowing and assembly logic. Reads are transactional so lazy
 * associations (category, tags) resolve safely during matching.</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class RecommendationService {

    /** Meal windows used for time-aware suggestions (end-exclusive hour). */
    private static final int BREAKFAST_END = 11;
    private static final int LUNCH_END = 16;
    private static final int SNACKS_END = 19;

    private static final Set<String> BREAKFAST_TERMS = Set.of("breakfast", "idli", "dosa", "poha", "paratha", "omelette", "cereal");
    private static final Set<String> LUNCH_TERMS = Set.of("lunch", "thali", "biryani", "rice", "combo");
    private static final Set<String> SNACKS_TERMS = Set.of("snack", "samosa", "chaat", "fries", "rolls");
    private static final Set<String> DINNER_TERMS = Set.of("dinner", "curry", "roti", "naan", "grill", "combo");

    private final RecommendationQueryService queryService;
    private final MenuItemRepository menuItemRepository;

    /** Overridden by config at runtime; inline default keeps plain unit tests deterministic. */
    @Value("${app.recommendations.max-items:10}")
    private int maxItems = 10;

    public RecommendationService(RecommendationQueryService queryService,
                                 MenuItemRepository menuItemRepository) {
        this.queryService = queryService;
        this.menuItemRepository = menuItemRepository;
    }

    /**
     * Items this customer orders repeatedly and can re-add to the cart with one tap.
     */
    public List<MenuItem> reorderSuggestions(Long customerId) {
        List<Long> ids = new ArrayList<>();
        for (Object[] row : queryService.findCustomerItemFrequencies(customerId, maxItems)) {
            ids.add(((Number) row[0]).longValue());
        }
        return queryService.hydrateAvailableItems(ids);
    }

    /**
     * Collaborative-filtering picks: popular companions of the customer's past
     * orders that they have not tried yet.
     */
    public List<MenuItem> collaborativeSuggestions(Long customerId) {
        List<Long> ownItemIds = new ArrayList<>();
        for (Object[] row : queryService.findCustomerItemFrequencies(customerId, 50)) {
            ownItemIds.add(((Number) row[0]).longValue());
        }
        if (ownItemIds.isEmpty()) {
            return List.of();
        }

        // Co-order candidates, minus anything the customer already orders.
        Set<Long> orderedAlready = new HashSet<>(ownItemIds);
        List<Long> candidateIds = new ArrayList<>();
        for (Object[] row : queryService.findCoOrderedItems(ownItemIds, customerId, maxItems * 3)) {
            long id = ((Number) row[0]).longValue();
            if (orderedAlready.add(id)) { // also dedupes candidates
                candidateIds.add(id);
            }
            if (candidateIds.size() >= maxItems) {
                break;
            }
        }
        return queryService.hydrateAvailableItems(candidateIds);
    }

    /**
     * Time-aware suggestions: what fits the current meal window. Falls back to the
     * customer's reorder history filtered by window, then to platform bestsellers.
     */
    public List<MenuItem> timeAwareSuggestions(Long customerId) {
        MealWindow window = MealWindow.current();
        List<MenuItem> candidates = reorderSuggestions(customerId);
        List<MenuItem> matching = filterByWindow(candidates, window);
        if (!matching.isEmpty()) {
            return matching;
        }

        // No personal history for this window — fall back to bestsellers tagged for it.
        List<MenuItem> fallback = new ArrayList<>();
        for (Long restaurantId : topRestaurantIds(customerId)) {
            menuItemRepository.findBestsellersByRestaurant(restaurantId).stream()
                    .filter(item -> matchesWindow(item, window))
                    .forEach(fallback::add);
            if (fallback.size() >= maxItems) {
                break;
            }
        }
        return fallback.size() > maxItems ? fallback.subList(0, maxItems) : fallback;
    }

    /**
     * Ranks the supplied candidate restaurants by how well their affinity profile
     * matches the customer's ordering history (frequency of past visits). Restaurants
     * the customer has never tried keep their original position after all ranked ones.
     */
    public List<Long> rankRestaurantsForCustomer(Long customerId, List<Long> candidateRestaurantIds) {
        Map<Long, Long> affinities = new LinkedHashMap<>();
        for (Object[] row : queryService.findCustomerRestaurantAffinities(customerId, 25)) {
            affinities.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }

        Map<Long, Long> position = new LinkedHashMap<>();
        for (int i = 0; i < candidateRestaurantIds.size(); i++) {
            position.put(candidateRestaurantIds.get(i), (long) i);
        }

        List<Long> ranked = new ArrayList<>(candidateRestaurantIds);
        ranked.sort((a, b) -> {
            long scoreA = affinities.getOrDefault(a, 0L);
            long scoreB = affinities.getOrDefault(b, 0L);
            if (scoreA != scoreB) {
                return Long.compare(scoreB, scoreA); // higher affinity first
            }
            return Long.compare(position.get(a), position.get(b)); // stable within score
        });
        return ranked;
    }

    private List<Long> topRestaurantIds(Long customerId) {
        List<Long> ids = new ArrayList<>();
        for (Object[] row : queryService.findCustomerRestaurantAffinities(customerId, 3)) {
            ids.add(((Number) row[0]).longValue());
        }
        return ids;
    }

    private List<MenuItem> filterByWindow(List<MenuItem> items, MealWindow window) {
        List<MenuItem> matching = new ArrayList<>();
        for (MenuItem item : items) {
            if (matchesWindow(item, window)) {
                matching.add(item);
            }
        }
        return matching;
    }

    private boolean matchesWindow(MenuItem item, MealWindow window) {
        String haystack = buildSearchableText(item);
        if (haystack.isEmpty()) {
            return false;
        }
        for (String term : window.terms) {
            if (haystack.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String buildSearchableText(MenuItem item) {
        StringBuilder sb = new StringBuilder();
        if (item.getName() != null) {
            sb.append(item.getName().toLowerCase(Locale.ROOT)).append(' ');
        }
        if (item.getTags() != null) {
            for (String tag : item.getTags()) {
                sb.append(tag.toLowerCase(Locale.ROOT)).append(' ');
            }
        }
        if (item.getCategory() != null && item.getCategory().getName() != null) {
            sb.append(item.getCategory().getName().toLowerCase(Locale.ROOT)).append(' ');
        }
        return sb.toString();
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
            if (hour < BREAKFAST_END) {
                return BREAKFAST;
            }
            if (hour < LUNCH_END) {
                return LUNCH;
            }
            if (hour < SNACKS_END) {
                return SNACKS;
            }
            return DINNER;
        }
    }
}
