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

    public Bucket getBucket(String name) {
        return buckets.getOrDefault(name, defaultBuckets().get(name));
    }

    private static Map<String, Bucket> defaultBuckets() {
        Map<String, Bucket> defaults = new HashMap<>();
        defaults.put("order-track", new Bucket(20, 60));
        defaults.put("kitchen-queue", new Bucket(30, 60));
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
