package com.bhukkad.ratelimit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    private Map<String, Bucket> buckets = defaultBuckets();

    /**
     * Tier multipliers applied on top of the base bucket limit. Premium users
     * get a higher ceiling than free users; the multiplier is applied as
     * {@code limit * multiplier} (rounded up).
     */
    private Map<String, Double> tierMultipliers = defaultTierMultipliers();

    public Bucket getBucket(String name) {
        return buckets.getOrDefault(name, defaultBuckets().get(name));
    }

    /** Effective limit for a bucket/tier combination. */
    public int effectiveLimit(String bucketName, String tier) {
        Bucket bucket = getBucket(bucketName);
        double multiplier = tier != null
                ? tierMultipliers.getOrDefault(tier.toLowerCase(), 1.0)
                : 1.0;
        return (int) Math.ceil(bucket.getLimit() * multiplier);
    }

    private static Map<String, Bucket> defaultBuckets() {
        Map<String, Bucket> defaults = new HashMap<>();
        defaults.put("order-track", new Bucket(20, 60));
        defaults.put("kitchen-queue", new Bucket(30, 60));
        defaults.put("search", new Bucket(60, 60));
        defaults.put("cart-mutation", new Bucket(30, 60));
        return defaults;
    }

    private static Map<String, Double> defaultTierMultipliers() {
        Map<String, Double> defaults = new HashMap<>();
        defaults.put("premium", 2.0);
        defaults.put("gold", 3.0);
        defaults.put("platinum", 4.0);
        defaults.put("free", 1.0);
        return defaults;
    }

    @Data
    public static class Bucket {
        private int limit;
        private int windowSeconds;

        public Bucket() {}

        public Bucket(int limit, int windowSeconds) {
            this.limit = limit;
            this.windowSeconds = windowSeconds;
        }
    }
}
