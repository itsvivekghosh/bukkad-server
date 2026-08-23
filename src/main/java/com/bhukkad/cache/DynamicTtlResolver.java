package com.bhukkad.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Time-of-day TTL scaling for cache entries (FEATURE #19).
 *
 * <p>During peak windows data churns faster (surge toggles, stock edits, campaign
 * changes), so entries are kept for a <em>shorter</em> time to stay fresh; off-peak
 * they may live longer for a higher hit ratio. Multipliers apply on top of the
 * static {@code cache.ttl.*} values.</p>
 *
 * <p>Peak windows parse from config, e.g. {@code peak-hours: "11:00-14:00,19:00-22:00"}.
 * Malformed entries are skipped with a warning — a broken clock must never break
 * caching. When disabled, {@link #resolve(long)} returns the base TTL unchanged.</p>
 */
@Slf4j
@Component
public class DynamicTtlResolver {

    private final boolean enabled;
    private final double peakMultiplier;
    private final double offPeakMultiplier;
    private final List<Window> peakWindows = new ArrayList<>();

    public DynamicTtlResolver(@Value("${app.cache.dynamic-ttl.enabled:false}") boolean enabled,
                              @Value("${app.cache.dynamic-ttl.peak-hours:}") String peakHours,
                              @Value("${app.cache.dynamic-ttl.peak-multiplier:0.5}") double peakMultiplier,
                              @Value("${app.cache.dynamic-ttl.off-peak-multiplier:1.5}") double offPeakMultiplier) {
        this.enabled = enabled;
        this.peakMultiplier = clamp(peakMultiplier);
        this.offPeakMultiplier = clamp(offPeakMultiplier);
        parseWindows(peakHours);
    }

    /**
     * Applies the current-window multiplier to a base TTL (seconds).
     *
     * @return at least 1 second so callers never produce an immediately-expiring entry.
     */
    public long resolve(long baseTtlSeconds) {
        if (!enabled || baseTtlSeconds <= 0) {
            return baseTtlSeconds;
        }
        LocalTime now = LocalTime.now();
        boolean peak = peakWindows.stream().anyMatch(w -> w.contains(now));
        long scaled = Math.round(baseTtlSeconds * (peak ? peakMultiplier : offPeakMultiplier));
        return Math.max(1L, scaled);
    }

    private void parseWindows(String spec) {
        if (spec == null || spec.isBlank()) {
            return;
        }
        for (String part : spec.split(",")) {
            String[] bounds = part.trim().split("-");
            if (bounds.length != 2) {
                log.warn("Ignoring malformed peak-hours entry: {}", part);
                continue;
            }
            try {
                peakWindows.add(new Window(LocalTime.parse(bounds[0].trim()), LocalTime.parse(bounds[1].trim())));
            } catch (Exception ex) {
                log.warn("Ignoring unparseable peak-hours entry: {} ({})", part, ex.getMessage());
            }
        }
    }

    private static double clamp(double multiplier) {
        if (multiplier <= 0 || Double.isNaN(multiplier)) {
            return 1.0;
        }
        return Math.min(10.0, multiplier);
    }

    record Window(LocalTime start, LocalTime end) {
        boolean contains(LocalTime time) {
            // Windows do not cross midnight in this model; "19:00-22:00" style only.
            return !time.isBefore(start) && !time.isAfter(end);
        }
    }
}
