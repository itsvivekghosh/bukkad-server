package com.bhukkad.recommendation;

import com.bhukkad.dto.response.MenuItemResponse;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.mapper.MenuItemMapper;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.OrderItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * "Surprise me" curated item pick (FEATURE #7).
 *
 * <p>Picks a single dish for a restaurant: the highest-rated available menu
 * item, falling back to the most-ordered available item when nothing has been
 * rated yet. The {@code userId} parameter is accepted for API symmetry with
 * the other per-customer recommendation endpoints and is reserved for future
 * personalization of the pick.</p>
 *
 * <p>The pick never throws: any lookup or mapping failure is logged as a warn
 * and surfaces as an empty {@link Optional}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SurpriseMeService {

    private final MenuItemRepository menuItemRepository;
    private final OrderItemRepository orderItemRepository;
    private final MenuItemMapper menuItemMapper;

    /**
     * @param userId       current customer id (reserved for personalization)
     * @param restaurantId restaurant to pick from
     * @return the picked item as a response, or empty when the restaurant has
     *         no available items or the pick failed
     */
    public Optional<MenuItemResponse> surprisePick(Long userId, Long restaurantId) {
        try {
            List<MenuItem> available = menuItemRepository.findByRestaurantIdWithDetails(restaurantId);
            if (available.isEmpty()) {
                return Optional.empty();
            }

            MenuItem bestRated = available.stream()
                    .max(ratingComparator())
                    .orElse(null);
            if (bestRated != null && isRated(bestRated)) {
                return Optional.of(menuItemMapper.toResponse(bestRated));
            }

            MenuItem mostOrdered = available.stream()
                    .max(mostOrderedComparator())
                    .orElse(null);
            return mostOrdered == null
                    ? Optional.empty()
                    : Optional.of(menuItemMapper.toResponse(mostOrdered));
        } catch (Exception ex) {
            log.warn("SURPRISE_ME_FAILED | restaurantId={} | error={}", restaurantId, ex.getMessage());
            return Optional.empty();
        }
    }

    private boolean isRated(MenuItem item) {
        return item.getAverageRating() != null && item.getAverageRating() > 0;
    }

    private Comparator<MenuItem> ratingComparator() {
        return (a, b) -> {
            double rating = Double.compare(safeRating(a), safeRating(b));
            if (rating != 0) {
                return (int) Math.signum(rating);
            }
            int counts = Integer.compare(safeRatings(a), safeRatings(b));
            return counts != 0 ? counts : Long.compare(a.getId(), b.getId());
        };
    }

    private Comparator<MenuItem> mostOrderedComparator() {
        return (a, b) -> {
            long ordered = Long.compare(
                    orderItemRepository.countByMenuItemId(a.getId()),
                    orderItemRepository.countByMenuItemId(b.getId()));
            if (ordered != 0) {
                return (int) Math.signum(ordered);
            }
            return Long.compare(a.getId(), b.getId());
        };
    }

    private static double safeRating(MenuItem item) {
        return item.getAverageRating() == null ? 0.0 : item.getAverageRating();
    }

    private static int safeRatings(MenuItem item) {
        return item.getTotalRatings() == null ? 0 : item.getTotalRatings();
    }
}
