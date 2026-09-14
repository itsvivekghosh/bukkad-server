package com.bhukkad.common.datasource;

/**
 * Simple shard router: maps a user ID to a shard schema name.
 *
 * <p>Sharding is by {@code user_id % SHARD_COUNT}, so the same user always
 * lands on the same shard. The current implementation uses 16 shards
 * ({@code shard_0} .. {@code shard_15}); expand by increasing {@link #SHARD_COUNT}
 * and rebalancing.
 */
public final class ShardRouter {

    public static final int SHARD_COUNT = 16;

    private ShardRouter() {
    }

    public static String shardFor(Long userId) {
        return "shard_" + Math.floorMod(userId, SHARD_COUNT);
    }

    public static int shardCount() {
        return SHARD_COUNT;
    }
}
