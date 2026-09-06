package com.bhukkad.common.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Deterministic cache-key builder. {@link #of(Object...)} hashes the parts so
 * keys are bounded in length regardless of argument size (e.g. long menu
 * payloads are never embedded in the key).
 */
public final class CacheKeyGenerator {

    private CacheKeyGenerator() {
    }

    public static String of(Object... parts) {
        if (parts == null || parts.length == 0) {
            throw new IllegalArgumentException("Cache key requires at least one part");
        }
        StringBuilder joined = new StringBuilder();
        for (Object part : parts) {
            joined.append(part).append(':');
        }
        return sha256(joined.toString());
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** True when all the given parts are non-null (guards against null keys). */
    public static boolean allPresent(Object... parts) {
        return Arrays.stream(parts).allMatch(p -> p != null);
    }

    /** Cache key for the full active restaurant list. */
    public static String restaurantList() {
        return of("restaurants", "all");
    }

    /** Cache key for a single restaurant detail. */
    public static String restaurant(Long restaurantId) {
        return of("restaurant", restaurantId);
    }
}
