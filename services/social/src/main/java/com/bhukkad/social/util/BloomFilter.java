package com.bhukkad.social.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.BitSet;

/**
 * Minimal Bloom filter for fast post-existence checks in geohash feed segments.
 *
 * <p>Uses a variable-size bit array and multiple hash functions derived from
 * MurmurHash3. The filter auto-sizes based on expected item count and a
 * target false-positive rate.</p>
 */
public final class BloomFilter {

    private static final int DEFAULT_SIZE = 8192; // ~1KB per filter
    private static final int DEFAULT_HASHES = 5;
    private static final double TARGET_FALSE_POSITIVE_RATE = 0.01; // 1%
    private static final int MIN_SIZE = 8192;

    private final BitSet bits;
    private final int size;
    private final int hashes;

    public BloomFilter() {
        this(DEFAULT_SIZE, DEFAULT_HASHES);
    }

    public BloomFilter(int size, int hashes) {
        this.size = size;
        this.hashes = hashes;
        this.bits = new BitSet(size);
    }

    /**
     * Add an element to the filter.
     */
    public void add(long value) {
        for (int hash : hashes(value)) {
            bits.set(Math.abs(hash % size));
        }
    }

    /**
     * Check if an element might be in the filter.
     *
     * @return {@code true} if the element might be present (possible false positives)
     */
    public boolean mightContain(long value) {
        for (int hash : hashes(value)) {
            if (!bits.get(Math.abs(hash % size))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Serialize to a compact byte array for Redis storage.
     * Format: 4-byte size (int) + bit array bytes
     */
    public byte[] toBytes() {
        byte[] bitBytes = bits.toByteArray();
        ByteBuffer buffer = ByteBuffer.allocate(4 + bitBytes.length);
        buffer.putInt(size);
        buffer.put(bitBytes);
        return buffer.array();
    }

    /**
     * Serialize to Base64 string for Redis storage.
     */
    public String toBase64() {
        return Base64.getEncoder().encodeToString(toBytes());
    }

    /**
     * Deserialize from a byte array.
     */
    public static BloomFilter fromBytes(byte[] data) {
        if (data == null || data.length < 4) {
            return new BloomFilter(DEFAULT_SIZE, DEFAULT_HASHES);
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        int size = buffer.getInt();
        if (size < MIN_SIZE) {
            size = MIN_SIZE;
        }
        BloomFilter filter = new BloomFilter(size, DEFAULT_HASHES);
        byte[] bitBytes = new byte[buffer.remaining()];
        buffer.get(bitBytes);
        BitSet bits = BitSet.valueOf(bitBytes);
        filter.bits.or(bits);
        return filter;
    }

    /**
     * Deserialize from Base64 string.
     */
    public static BloomFilter fromBase64(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        byte[] data = Base64.getDecoder().decode(base64);
        return fromBytes(data);
    }

    /**
     * Build a filter from a list of post IDs with optimal sizing.
     */
    public static BloomFilter build(java.util.List<Long> postIds) {
        int count = postIds.size();
        if (count == 0) {
            return new BloomFilter(DEFAULT_SIZE, DEFAULT_HASHES);
        }
        // Optimal bit count: m = -(n * ln(p)) / (ln(2)^2)
        int optimalSize = (int) Math.ceil(-count * Math.log(TARGET_FALSE_POSITIVE_RATE) / Math.pow(Math.log(2), 2));
        optimalSize = Math.max(MIN_SIZE, optimalSize);
        // Optimal hash count: k = (m/n) * ln(2)
        int optimalHashes = Math.max(1, (int) Math.round((optimalSize / (double) count) * Math.log(2)));
        optimalHashes = Math.min(optimalHashes, 10); // cap hashes for performance

        BloomFilter filter = new BloomFilter(optimalSize, optimalHashes);
        for (Long id : postIds) {
            filter.add(id);
        }
        return filter;
    }

    /**
     * Generate multiple hash values using MurmurHash3-style mixing.
     */
    private int[] hashes(long value) {
        int[] result = new int[hashes];
        byte[] bytes = Long.toString(value).getBytes(StandardCharsets.UTF_8);
        int hash1 = murmurHash(bytes, 0);
        int hash2 = murmurHash(bytes, hash1);
        for (int i = 0; i < hashes; i++) {
            result[i] = Math.abs(hash1 + i * hash2);
        }
        return result;
    }

    private int murmurHash(byte[] data, int seed) {
        int h = seed ^ data.length;
        for (byte b : data) {
            int x = (h ^ (b & 0xFF)) * 0x5BD1E995;
            h = (x ^ (x >>> 15));
        }
        return h;
    }
}
