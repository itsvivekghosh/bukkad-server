package com.bhukkad.serviceImpl;

import com.bhukkad.datasource.UseReadReplica;
import com.bhukkad.dto.response.RestaurantAnalyticsResponse;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.repository.OrderItemRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.RestaurantAnalyticsService;
import com.bhukkad.util.PriceCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RestaurantAnalyticsServiceImpl implements RestaurantAnalyticsService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final RestaurantRepository restaurantRepository;
    private final SecurityUtils securityUtils;

    @Override
    @UseReadReplica
    public RestaurantAnalyticsResponse getAnalytics(Long restaurantId, int days) {
        int periodDays = Math.min(Math.max(days, 1), 90);
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));
        verifyOwnership(restaurant);

        LocalDateTime startDate = LocalDateTime.now().minusDays(periodDays);
        long totalOrders = orderRepository.countByRestaurantIdAndCreatedAtAfter(restaurantId, startDate);
        long deliveredOrders = orderRepository.countByRestaurantIdAndStatusAndCreatedAtAfter(
                restaurantId, Order.OrderStatus.DELIVERED, startDate);
        long cancelledOrders = orderRepository.countByRestaurantIdAndStatusAndCreatedAtAfter(
                restaurantId, Order.OrderStatus.CANCELLED, startDate);
        Double totalRevenue = orderRepository.sumRestaurantRevenueSince(restaurantId, startDate);

        Map<String, Long> ordersByStatus = orderRepository
                .countRestaurantOrdersGroupedByStatus(restaurantId, startDate)
                .stream()
                .collect(Collectors.toMap(
                        row -> ((Order.OrderStatus) row[0]).name(),
                        row -> (Long) row[1],
                        Long::sum,
                        LinkedHashMap::new));

        List<RestaurantAnalyticsResponse.TopMenuItemStat> topItems = orderItemRepository
                .findTopSellingItems(restaurantId, startDate)
                .stream()
                .limit(10)
                .map(row -> RestaurantAnalyticsResponse.TopMenuItemStat.builder()
                        .menuItemId((Long) row[0])
                        .name((String) row[1])
                        .quantitySold((Long) row[2])
                        .revenue(PriceCalculator.roundToTwoDecimals((Double) row[3]))
                        .build())
                .collect(Collectors.toList());

        List<RestaurantAnalyticsResponse.DailyRevenueStat> dailyRevenue = buildDailyRevenue(
                restaurantId, periodDays);

        double revenue = totalRevenue != null ? totalRevenue : 0.0;
        return RestaurantAnalyticsResponse.builder()
                .restaurantId(restaurantId)
                .restaurantName(restaurant.getName())
                .periodDays(periodDays)
                .totalRevenue(revenue)
                .totalOrders(totalOrders)
                .deliveredOrders(deliveredOrders)
                .cancelledOrders(cancelledOrders)
                .averageOrderValue(deliveredOrders > 0 ? PriceCalculator.roundToTwoDecimals(revenue / deliveredOrders) : 0.0)
                .ordersByStatus(ordersByStatus)
                .topMenuItems(topItems)
                .dailyRevenue(dailyRevenue)
                .build();
    }

    private List<RestaurantAnalyticsResponse.DailyRevenueStat> buildDailyRevenue(Long restaurantId, int periodDays) {
        List<RestaurantAnalyticsResponse.DailyRevenueStat> stats = new ArrayList<>();
        for (int i = periodDays - 1; i >= 0; i--) {
            LocalDate day = LocalDate.now().minusDays(i);
            LocalDateTime dayStart = day.atStartOfDay();
            LocalDateTime dayEnd = day.plusDays(1).atStartOfDay();
            long count = orderRepository.countByRestaurantIdAndStatusAndCreatedAtAfter(
                    restaurantId, Order.OrderStatus.DELIVERED, dayStart);
            // Approximate daily revenue via sum since dayStart filtered — refine with between query if needed
            Double dayRevenue = orderRepository.sumRestaurantRevenueSince(restaurantId, dayStart);
            stats.add(RestaurantAnalyticsResponse.DailyRevenueStat.builder()
                    .date(day.toString())
                    .revenue(dayRevenue != null ? dayRevenue : 0.0)
                    .orderCount(count)
                    .build());
        }
        return stats;
    }

    private void verifyOwnership(Restaurant restaurant) {
        Long currentUserId = securityUtils.getCurrentUserId();
        if (!restaurant.getOwner().getId().equals(currentUserId)) {
            throw new UnauthorizedException("You do not own this restaurant");
        }
    }
}
