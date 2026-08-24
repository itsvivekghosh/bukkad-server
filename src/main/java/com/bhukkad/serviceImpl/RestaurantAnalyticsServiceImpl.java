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
import java.util.HashMap;
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

        // Peak-hour analytics from a single grouped query (0-23 buckets).
        List<RestaurantAnalyticsResponse.HourlyVolumeStat> hourlyVolume = buildHourlyVolume(
                restaurantId, startDate);
        int peakHourOfDay = -1;
        long peakHourOrderCount = 0;
        for (RestaurantAnalyticsResponse.HourlyVolumeStat stat : hourlyVolume) {
            if (stat.getOrderCount() > peakHourOrderCount) {
                peakHourOrderCount = stat.getOrderCount();
                peakHourOfDay = stat.getHour();
            }
        }

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
                .hourlyVolume(hourlyVolume)
                .peakHourOfDay(peakHourOfDay)
                .peakHourOrderCount(peakHourOrderCount)
                .build();
    }

    /**
     * Builds one row per day in the window, backed by a single grouped query.
     *
     * <p>The previous implementation called {@code countBy...After(dayStart)} and
     * {@code sumRevenueSince(dayStart)} once per day, which produced cumulative
     * (running-total) figures instead of per-day numbers and made N×2 database
     * round trips. The grouped query returns exact per-day totals; days with no
     * delivered orders are back-filled with zeros so the series is continuous.
     */
    private List<RestaurantAnalyticsResponse.DailyRevenueStat> buildDailyRevenue(
            Long restaurantId, int periodDays) {
        Map<LocalDate, RestaurantAnalyticsResponse.DailyRevenueStat> byDay = new HashMap<>();
        for (Object[] row : orderRepository.findDailyDeliveredAggregates(
                restaurantId, LocalDateTime.now().minusDays(periodDays))) {
            LocalDate day = ((java.sql.Date) row[0]).toLocalDate();
            long count = ((Number) row[1]).longValue();
            double dayRevenue = row[2] != null ? ((Number) row[2]).doubleValue() : 0.0;
            byDay.put(day, RestaurantAnalyticsResponse.DailyRevenueStat.builder()
                    .date(day.toString())
                    .revenue(PriceCalculator.roundToTwoDecimals(dayRevenue))
                    .orderCount(count)
                    .build());
        }

        List<RestaurantAnalyticsResponse.DailyRevenueStat> stats = new ArrayList<>(periodDays);
        for (int i = periodDays - 1; i >= 0; i--) {
            LocalDate day = LocalDate.now().minusDays(i);
            RestaurantAnalyticsResponse.DailyRevenueStat stat = byDay.get(day);
            stats.add(stat != null ? stat : RestaurantAnalyticsResponse.DailyRevenueStat.builder()
                    .date(day.toString())
                    .revenue(0.0)
                    .orderCount(0L)
                    .build());
        }
        return stats;
    }

    /**
     * Maps the grouped per-hour counts to a dense 0-23 series so consumers can
     * render a full day without filling gaps themselves.
     */
    private List<RestaurantAnalyticsResponse.HourlyVolumeStat> buildHourlyVolume(
            Long restaurantId, LocalDateTime startDate) {
        long[] perHour = new long[24];
        for (Object[] row : orderRepository.findHourlyDeliveredCounts(restaurantId, startDate)) {
            int hour = ((Number) row[0]).intValue();
            if (hour >= 0 && hour < 24) {
                perHour[hour] = ((Number) row[1]).longValue();
            }
        }
        List<RestaurantAnalyticsResponse.HourlyVolumeStat> volume = new ArrayList<>(24);
        for (int hour = 0; hour < 24; hour++) {
            volume.add(RestaurantAnalyticsResponse.HourlyVolumeStat.builder()
                    .hour(hour)
                    .orderCount(perHour[hour])
                    .build());
        }
        return volume;
    }

    private void verifyOwnership(Restaurant restaurant) {
        Long currentUserId = securityUtils.getCurrentUserId();
        if (!restaurant.getOwner().getId().equals(currentUserId)) {
            throw new UnauthorizedException("You do not own this restaurant");
        }
    }
}
