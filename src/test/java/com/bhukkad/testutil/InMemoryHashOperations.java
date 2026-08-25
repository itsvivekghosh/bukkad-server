package com.bhukkad.testutil;

import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.ScanOptions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test-only in-memory {@link HashOperations} backed by a
 * {@link ConcurrentHashMap}, mirroring the semantics of a Redis hash for
 * deterministic unit tests (no container needed).
 *
 * <p>Used to verify Redis-backed state in multi-replica scenarios: two service
 * instances sharing the same backing map observe each other's writes exactly
 * like two replicas sharing one Redis.</p>
 */
public class InMemoryHashOperations<H, HK, HV> implements HashOperations<H, HK, HV> {

    private final Map<H, Map<HK, HV>> store = new ConcurrentHashMap<>();

    public void clear() {
        store.clear();
    }

    @Override
    public RedisOperations<H, ?> getOperations() {
        return null;
    }

    @Override
    public Long delete(H key, Object... hashKeys) {
        Map<HK, HV> hash = store.get(key);
        if (hash == null) {
            return 0L;
        }
        long removed = 0;
        for (Object hashKey : hashKeys) {
            if (hash.remove(hashKey) != null) {
                removed++;
            }
        }
        return removed;
    }

    @Override
    public Boolean hasKey(H key, Object hashKey) {
        Map<HK, HV> hash = store.get(key);
        return hash != null && hash.containsKey(hashKey);
    }

    @Override
    public HV get(H key, Object hashKey) {
        Map<HK, HV> hash = store.get(key);
        return hash == null ? null : hash.get(hashKey);
    }

    @Override
    public List<HV> multiGet(H key, Collection<HK> hashKeys) {
        Map<HK, HV> hash = store.get(key);
        List<HV> result = new ArrayList<>();
        if (hash != null) {
            for (HK hashKey : hashKeys) {
                result.add(hash.get(hashKey));
            }
        }
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Long increment(H key, HK hashKey, long delta) {
        Map<HK, HV> hash = store.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        long current = hash.containsKey(hashKey) ? ((Number) hash.get(hashKey)).longValue() : 0L;
        long newVal = current + delta;
        hash.put(hashKey, (HV) Long.valueOf(newVal));
        return newVal;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Double increment(H key, HK hashKey, double delta) {
        Map<HK, HV> hash = store.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        double current = hash.containsKey(hashKey) ? ((Number) hash.get(hashKey)).doubleValue() : 0.0;
        double newVal = current + delta;
        hash.put(hashKey, (HV) Double.valueOf(newVal));
        return newVal;
    }

    @Override
    public Set<HK> keys(H key) {
        Map<HK, HV> hash = store.get(key);
        return hash == null ? Set.of() : Set.copyOf(hash.keySet());
    }

    @Override
    public Long lengthOfValue(H key, HK hashKey) {
        HV value = get(key, hashKey);
        return value == null ? 0L : (long) String.valueOf(value).length();
    }

    @Override
    public Long size(H key) {
        Map<HK, HV> hash = store.get(key);
        return hash == null ? 0L : (long) hash.size();
    }

    @Override
    public void putAll(H key, Map<? extends HK, ? extends HV> m) {
        Map<HK, HV> hash = store.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        hash.putAll(m);
    }

    @Override
    public void put(H key, HK hashKey, HV value) {
        Map<HK, HV> hash = store.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        hash.put(hashKey, value);
    }

    @Override
    public Boolean putIfAbsent(H key, HK hashKey, HV value) {
        Map<HK, HV> hash = store.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        return hash.putIfAbsent(hashKey, value) == null;
    }

    @Override
    public Map<HK, HV> entries(H key) {
        Map<HK, HV> hash = store.get(key);
        return hash == null ? Map.of() : new LinkedHashMap<>(hash);
    }

    @Override
    public List<HV> values(H key) {
        Map<HK, HV> hash = store.get(key);
        return hash == null ? List.of() : new ArrayList<>(hash.values());
    }

    @Override
    public HK randomKey(H key) {
        Map<HK, HV> hash = store.get(key);
        if (hash == null || hash.isEmpty()) {
            return null;
        }
        return hash.keySet().iterator().next();
    }

    @Override
    public Map.Entry<HK, HV> randomEntry(H key) {
        Map<HK, HV> hash = store.get(key);
        if (hash == null || hash.isEmpty()) {
            return null;
        }
        HK k = hash.keySet().iterator().next();
        return Map.entry(k, hash.get(k));
    }

    @Override
    public List<HK> randomKeys(H key, long count) {
        Map<HK, HV> hash = store.get(key);
        if (hash == null || hash.isEmpty()) {
            return List.of();
        }
        List<HK> keys = new ArrayList<>(hash.keySet());
        return keys.subList(0, (int) Math.min(count, keys.size()));
    }

    @Override
    public Map<HK, HV> randomEntries(H key, long count) {
        Map<HK, HV> hash = entries(key);
        List<HK> keys = new ArrayList<>(hash.keySet());
        int limit = (int) Math.min(count, keys.size());
        Map<HK, HV> result = new LinkedHashMap<>();
        for (int i = 0; i < limit; i++) {
            result.put(keys.get(i), hash.get(keys.get(i)));
        }
        return result;
    }

    @Override
    public Cursor<Map.Entry<HK, HV>> scan(H key, ScanOptions options) {
        Map<HK, HV> snapshot = new LinkedHashMap<>(entries(key));
        List<Map.Entry<HK, HV>> list = new ArrayList<>(snapshot.entrySet());
        return new Cursor<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < list.size();
            }

            @Override
            public Map.Entry<HK, HV> next() {
                return list.get(index++);
            }

            @Override
            public long getCursorId() {
                return 0;
            }

            @Override
            public boolean isClosed() {
                return index >= list.size();
            }

            @Override
            public long getPosition() {
                return index;
            }

            @Override
            public void close() {
                // no-op
            }
        };
    }
}