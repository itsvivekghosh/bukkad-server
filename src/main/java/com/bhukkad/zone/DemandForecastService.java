package com.bhukkad.zone;

import com.bhukkad.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Computes a platform-level demand index for the current hour of day based on
 * historical order volume. The index is a multiplier relative to the average
 * hourly volume: &gt;1.0 means the current hour is typically busier than average,
 * &lt;1.0 means quieter.
 *
 * <p>Used by {@link ZoneSurgeService} to adjust surge multipliers so delivery
 * fees and rider incentives reflect predicted demand, not just current rules.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemandForecastService {

    /**
     * Number of days of historical data used for the forecast. Longer windows
     * smooth out anomalies; shorter windows react faster to weekly patterns.
     */
    static final int FORECAST_WINDOW_DAYS = 14;

    /**
     * Clamp the forecast adjustment so surge never goes below 0.8× or above
     * 2.0× from the forecast alone (rules and zone base sit on top).
     */
    static final double MIN_ADJUSTMENT = 0.8;
    static final double MAX_ADJUSTMENT = 2.0;

    private final OrderRepository orderRepository;

    /**
     * Returns a demand-index multiplier for the current hour of day.
     *
     * <p>A value of 1.0 means the current hour's historical volume matches the
     * platform average; 1.5 means 50 % busier than average; 0.7 means 30 %
     * quieter.
     */
    public double forecastSurgeAdjustment() {
        try {
            LocalDateTime since = LocalDateTime.now().minusDays(FORECAST_WINDOW_DAYS);
            List<Object[]> rows = orderRepository.findPlatformHourlyOrderCounts(since);

            if (rows == null || rows.isEmpty()) {
                return 1.0;
            }

            // Build the hourly histogram and compute the global average.
            long[] hourlyCounts = new long[24];
            long total = 0;
            for (Object[] row : rows) {
                int hour = ((Number) row[0]).intValue();
                long count = ((Number) row[1]).longValue();
                if (hour >= 0 && hour < 24) {
                    hourlyCounts[hour] = count;
                    total += count;
                }
            }

            // Number of days of data actually available (hours with data / 24).
            // At least 1 to avoid division by zero.
            long daysWithData = Math.max(1, rows.size() / 24);
            double averagePerHour = (double) total / (24 * daysWithData);

            if (averagePerHour <= 0) {
                return 1.0;
            }

            int currentHour = LocalDateTime.now().getHour();
            double currentHourAvg = (double) hourlyCounts[currentHour] / daysWithData;
            double adjustment = currentHourAvg / averagePerHour;

            return clamp(adjustment, MIN_ADJUSTMENT, MAX_ADJUSTMENT);
        } catch (Exception ex) {
            log.warn("Demand forecast failed, using neutral adjustment | error={}", ex.getMessage());
            return 1.0;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}