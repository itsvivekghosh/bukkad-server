# Bhukkad Social Commerce Platform — High-Performance Implementation Guide (50K+ TPS)

## Document Control

| Field | Value |
|-------|-------|
| **Scope** | Backend implementation for location-centric social commerce, optimized for 50K+ TPS post wall fetch and high-throughput ordering |
| **Stack** | Java 17, Spring Boot 3.2, PostgreSQL 16 + PostGIS, Redis 7 Cluster, Kafka, Elasticsearch 8.x |
| **Target Services** | `restaurant`, `social` (new), `order`, `search`, `gateway` |
| **Auth** | JWT HS256/RS256 via `platform-lib` (CUSTOMER, ADMIN scopes) |
| **Performance Targets** | Post wall fetch: 50K+ TPS, p95 < 100ms; Order creation: 10K+ TPS, p95 < 200ms; Search: 5K+ QPS, p95 < 50ms |

---

## 1. Executive Summary

This plan redesigns the bhukkad backend for **production-scale location-centric social commerce** with explicit optimization for **50,000+ TPS post wall fetch** and **high-throughput contextual ordering**.

### 1.1 Core Architectural Principles for Scale

| Principle | Implementation |
|-----------|----------------|
| **Read-optimized everywhere** | Multi-level caching, pre-computed feeds, read replicas |
| **Write-behind everywhere** | Redis hot counters, async DB sync, outbox pattern |
| **Shard by natural keys** | Geohash for spatial, restaurant_id for social, user_id for personalization |
| **Avoid热点 (hot spots)** | Consistent hashing, partition pruning, request coalescing |
| **Fail-fast with fallback** | Circuit breakers, stale-while-revalidate, degraded modes |
| **Zero-copy data flow** | Kafka as canonical event log, no polling, no dual-write race |

### 1.2 Key Performance Targets

| Component | TPS Target | p95 Latency | p99 Latency |
|-----------|------------|-------------|-------------|
| **Post Wall Fetch** | 50,000+ | < 100ms | < 200ms |
| **Like/Unlike** | 100,000+ | < 5ms | < 10ms |
| **Comment Create** | 10,000+ | < 50ms | < 100ms |
| **Order from Post** | 10,000+ | < 200ms | < 500ms |
| **Search** | 5,000+ QPS | < 50ms | < 100ms |
| **Restaurant Nearby** | 20,000+ | < 50ms | < 100ms |

### 1.3 Optimization-First Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         CLIENT / MOBILE APP                              │
└────────────────────────────┬────────────────────────────────────────────┘
                             │ HTTPS/2
                             ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         SPRING CLOUD GATEWAY                            │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────────────────────┐    │
│  │ Rate Limit  │  │   Cache      │  │   Circuit Breaker            │    │
│  │ (Redis Lua) │  │  (Edge CDN)  │  │   (Resilience4j)            │    │
│  └─────────────┘  └──────────────┘  └─────────────────────────────┘    │
└────────────────────────────┬────────────────────────────────────────────┘
                             │
          ┌──────────────────┼──────────────────┐
          ▼                  ▼                  ▼
┌─────────────────┐ ┌──────────────┐ ┌──────────────────┐
│   REDIS CLUSTER │ │   KAFKA      │ │ ELASTICSEARCH    │
│  ┌────────────┐ │ │              │ │  ┌────────────┐  │
│  │ Geo Sorted │ │ │  ┌────────┐  │ │  │   Index    │  │
│  │ Sets      │ │ │  │ Topics │  │ │  │ Shards    │  │
│  │ (Hot Cache)│ │ │  │        │  │ │  │ (5 shards)│  │
│  ├────────────┤ │ │  │ social │  │ │  ├────────────┤  │
│  │ Counters  │ │ │  │ feed   │  │ │  │ Replicas  │  │
│  │ (Likes)   │ │ │  │ search │  │ │  │ (1 each)  │  │
│  ├────────────┤ │ │  │ order  │  │ │  └────────────┘  │
│  │ Pub/Sub   │ │ │  └────────┘  │ │                  │
│  │ (Invalid.) │ │ │              │ │                  │
│  ├────────────┤ │ │  6 Brokers   │ │                  │
│  │ JSON Doc  │ │ │  Replication │ │                  │
│  │ Cache     │ │ │  Factor: 3   │ │                  │
│  └────────────┘ │ └──────────────┘ └──────────────────┘  │
│  ┌────────────┐ │         │              │
│  │ Bloom     │ │         ▼              ▼
│  │ Filters   │ │  ┌──────────────┐  ┌──────────────┐
│  │ (Post IDs)│ │  │   POSTGRES   │  │   POSTGRES   │
│  └────────────┘ │  │  Primary +   │  │  Read        │
│  3 Masters,     │  │  2 Replicas  │  │  Replicas    │
│  5 Replicas     │  │  (PostGIS)   │  │  (Search)    │
│                 │  └──────────────┘  └──────────────┘
└─────────────────┘

LEGEND:
  ████████  Hot path (sub-10ms)
  ████████  Warm path (10-100ms)
  ████████  Cold path (async, >100ms)
```

---

## 2. Phase 1: Spatial Foundation — Ultra-Low Latency Geospatial

**Goal**: Sub-50ms geospatial queries at 20K+ TPS with PostGIS + multi-tier caching.

### 2.1 Database Optimization Strategy

#### 2.1.1 PostGIS with Partition Pruning

**File**: `services/restaurant/src/main/resources/db/migration-pg/V20260916__enable_postgis_optimized.sql`

```sql
-- Enable PostGIS
CREATE EXTENSION IF NOT EXISTS postgis;

-- Add geography column (WGS84)
ALTER TABLE restaurants
    ADD COLUMN IF NOT EXISTS geog GEOGRAPHY(Point, 4326);

-- Backfill in batches to avoid locking
DO $$
DECLARE
    batch_size INT := 10000;
    rows_updated INT;
BEGIN
    LOOP
        UPDATE restaurants
        SET geog = ST_MakePoint(longitude, latitude)::GEOGRAPHY
        WHERE geog IS NULL
        LIMIT batch_size;
        GET DIAGNOSTICS rows_updated = ROW_COUNT;
        EXIT WHEN rows_updated = 0;
        COMMIT;  -- Release locks between batches
        PERFORM pg_sleep(0.1);  -- Brief pause to reduce I/O spike
    END LOOP;
END $$;

-- Make NOT NULL after backfill
ALTER TABLE restaurants ALTER COLUMN geog SET NOT NULL;

-- GiST index for radius queries
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_restaurants_geog
    ON restaurants USING GIST (geog);

-- Covering index for nearby query (include frequently accessed columns)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_restaurants_nearby_covering
    ON restaurants USING GIST (geog)
    INCLUDE (id, name, latitude, longitude, is_active, delivery_radius_km, average_rating);

-- Partial index for active restaurants only (reduces index size by ~30%)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_restaurants_active_geog
    ON restaurants USING GIST (geog)
    WHERE is_active = true;

-- BRIN index for time-based data (if restaurant table is append-only)
-- Useful for "new restaurants" queries
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_restaurants_created_brin
    ON restaurants USING BRIN (created_at);

-- Analyze for query planner
ANALYZE restaurants;
```

**Validation Query**:
```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT id, name, latitude, longitude,
       ST_Distance(geog, ST_MakePoint(77.5946, 12.9716)::GEOGRAPHY) AS distance_meters
FROM restaurants
WHERE ST_DWithin(geog, ST_MakePoint(77.5946, 12.9716)::GEOGRAPHY, 5000)
  AND is_active = true
ORDER BY distance_meters ASC
LIMIT 100;
-- Expected: Index Scan using idx_restaurants_active_geog, < 5ms execution
```

#### 2.1.2 Connection Pooling Optimization

**HikariCP Tuning** (`application.yml`):
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 50          -- Per service instance
      minimum-idle: 10
      connection-timeout: 1000        -- 1s
      idle-timeout: 600000            -- 10min
      max-lifetime: 1800000           -- 30min (less than DB timeout)
      leak-detection-threshold: 5000  -- 5s
      pool-name: "restaurant-pool"
      auto-commit: true               -- Read-heavy workload
```

**PostgreSQL `pgbouncer`** (transaction pooling):
```ini
[databases]
restaurant_db = host=postgres-primary port=5432 dbname=restaurant_db

[pgbouncer]
listen_addr = 0.0.0.0
listen_port = 6432
auth_type = md5
auth_file = /etc/pgbouncer/userlist.txt
pool_mode = transaction
max_client_conn = 10000
default_pool_size = 50
reserve_pool_size = 10
```

### 2.2 Redis Cluster for Horizontal Scale

#### 2.2.1 Redis Cluster Topology

```
┌─────────────────────────────────────────────────────────────┐
│                    REDIS CLUSTER                            │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐        │
│  │   Master 1  │  │   Master 2  │  │   Master 3  │        │
│  │ (Hash Slot  │  │ (Hash Slot  │  │ (Hash Slot  │        │
│  │   0-5460)   │  │  5461-10922)│  │ 10923-16383)│        │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘        │
│         │                │                │                 │
│  ┌──────┴──────┐  ┌──────┴──────┐  ┌──────┴──────┐        │
│  │ Replica 1A  │  │ Replica 2A  │  │ Replica 3A  │        │
│  │ (Geo+Cache) │  │ (Counters)  │  │ (Feed IDs)  │        │
│  └─────────────┘  └─────────────┘  └─────────────┘        │
└─────────────────────────────────────────────────────────────┘

Data Distribution Strategy:
  - Slot 0-5460:   Restaurant GEO data + nearby cache (shard by geohash)
  - Slot 5461-10922: Post counters + like sets (shard by post_id)
  - Slot 10923-16383: Feed ID sorted sets (shard by geohash + radius)
```

#### 2.2.2 Geohash-Based Cache Sharding

**Key Design**: Instead of caching `feed:{geohash}:{radius}`, shard by geohash prefix to distribute load across cluster nodes.

```java
public class GeohashCacheKey {
    private static final int PREFIX_LENGTH = 4;  -- ~20km x 20km cells
    
    public static String buildFeedKey(double lat, double lng, double radiusKm) {
        String geohash = GeohashUtils.encode(lat, lng, PREFIX_LENGTH);
        String radiusBucket = radiusBucket(radiusKm);
        return "feed:" + geohash + ":" + radiusBucket;
    }
    
    private static String radiusBucket(double radiusKm) {
        // Logarithmic bucketing: 0-1km, 1-2km, 2-5km, 5-10km, 10-20km, 20-50km, 50-100km
        if (radiusKm <= 1) return "1";
        if (radiusKm <= 2) return "2";
        if (radiusKm <= 5) return "5";
        if (radiusKm <= 10) return "10";
        if (radiusKm <= 20) return "20";
        if (radiusKm <= 50) return "50";
        return "100";
    }
}
```

**Multi-Level Cache Architecture**:
```java
@Component
public class MultiLevelFeedCache {
    private final Caffeine<Object, Object> l1Cache;       -- 5min TTL, 10K entries
    private final RedisTemplate<String, String> redisTemplate;  -- L2, 1hr TTL
    private final RestaurantServiceClient restaurantClient;
    
    private static final String FEED_L1_PREFIX = "feed:l1:";
    private static final String FEED_L2_PREFIX = "feed:l2:";
    
    public FeedResult getNearbyFeed(double lat, double lng, double radiusKm, 
                                    String cursor, int size) {
        String l1Key = FEED_L1_PREFIX + buildKey(lat, lng, radiusKm, cursor);
        String l2Key = FEED_L2_PREFIX + buildKey(lat, lng, radiusKm, cursor);
        
        // L1: Caffeine (in-JVM, ~1ms)
        FeedResult l1 = (FeedResult) l1Cache.getIfPresent(l1Key);
        if (l1 != null) return l1;
        
        // L2: Redis Cluster (~5ms)
        String cached = redisTemplate.opsForValue().get(l2Key);
        if (cached != null) {
            FeedResult result = JsonUtils.fromJson(cached, FeedResult.class);
            // Populate L1 for next request
            l1Cache.put(l1Key, result);
            return result;
        }
        
        // L3: Database + Redis GEO (~50ms)
        FeedResult result = loadFromDatabase(lat, lng, radiusKm, cursor, size);
        
        // Populate L2 (longer TTL)
        redisTemplate.opsForValue().set(l2Key, JsonUtils.toJson(result), 
                                       1, TimeUnit.HOURS);
        // Populate L1 (shorter TTL)
        l1Cache.put(l1Key, result);
        
        return result;
    }
}
```

### 2.3 PostGIS Read Replicas

**Architecture**:
```
                    ┌─────────────────┐
                    │  PostgreSQL     │
                    │  Primary        │
                    │  (Write)        │
                    └────────┬────────┘
                             │ Streaming Replication
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
       ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
       │ Replica 1   │ │ Replica 2   │ │ Replica 3   │
       │ (Geospatial)│ │ (Feed Read) │ │ (Analytics) │
       └─────────────┘ └─────────────┘ └─────────────┘
```

**Routing Strategy**:
```java
@Configuration
public class PostgresRoutingConfig {
    
    @Bean
    @Primary
    public DataSource routingDataSource(
            @Qualifier("primaryDataSource") DataSource primary,
            @Qualifier("replica1DataSource") DataSource replica1,
            @Qualifier("replica2DataSource") DataSource replica2
    ) {
        Map<Object, Object> dataSources = new HashMap<>();
        dataSources.put("primary", primary);
        dataSources.put("replica1", replica1);
        dataSources.put("replica2", replica2);
        
        RoutingDataSource routingDataSource = new RoutingDataSource();
        routingDataSource.setDefaultTargetDataSource(primary);
        routingDataSource.setTargetDataSources(dataSources);
        return routingDataSource;
    }
}

// Usage:
// @ReadFrom("replica1")  -- For geospatial queries
// @ReadFrom("replica2")  -- For feed queries
// @WriteTo("primary")    -- For writes
```

### 2.4 Geospatial Query Optimization

**File**: `services/restaurant/src/main/java/com/bhukkad/restaurant/domain/service/LocationService.java`

```java
@Service
@Slf4j
public class LocationService {
    private final RestaurantRepository restaurantRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final CaffeineCache l1Cache;
    private final CounterManager counterManager;
    
    private static final String GEO_HASH_PREFIX = "restaurants:geo:";
    private static final String NEARBY_PREFIX = "restaurants:nearby:";
    
    /**
     * Ultra-optimized nearby query with multi-tier caching.
     * Target: < 50ms p95 at 20K TPS.
     */
    public List<RestaurantSummary> findNearby(double lat, double lng, double radiusKm) {
        // 1. L1 Cache (Caffeine) - ~1ms
        String l1Key = NEARBY_PREFIX + buildKey(lat, lng, radiusKm);
        List<RestaurantSummary> l1Result = l1Cache.get(l1Key, k -> null);
        if (l1Result != null) return l1Result;
        
        // 2. Redis GEO acceleration - ~5ms
        String geohash = GeohashUtils.encode(lat, lng, 4);  -- ~20km cells
        List<RestaurantSummary> redisResult = queryRedisGeo(geohash, lat, lng, radiusKm);
        if (redisResult != null && !redisResult.isEmpty()) {
            l1Cache.put(l1Key, redisResult);
            return redisResult;
        }
        
        // 3. PostGIS with read replica - ~20ms
        List<RestaurantSummary> dbResult = queryPostGISReplica(lat, lng, radiusKm);
        
        // 4. Populate Redis GEO asynchronously (fire-and-forget)
        CompletableFuture.runAsync(() -> populateRedisGeo(geohash, dbResult));
        
        // 5. Cache result
        l1Cache.put(l1Key, dbResult);
        return dbResult;
    }
    
    private List<RestaurantSummary> queryRedisGeo(String geohash, double lat, double lng, double radiusKm) {
        // GEORADIUS on geohash-prefixed key set
        String key = GEO_HASH_PREFIX + geohash;
        Distance radius = new Distance(radiusKm, Metrics.KILOMETERS);
        
        List<GeoLocation> locations = redisTemplate.opsForGeo()
            .radius(key, 
                    new Circle(lng, lat, radius),
                    GeoRadiusCommandArgs.newGeoRadiusArgs()
                        .includeDistance(true)
                        .sortAscending())
            .stream()
            .map(result -> new GeoLocation(
                result.getMember().toString(),
                result.getPoint().getX(),  // lng
                result.getPoint().getY()   // lat
            ))
            .toList();
        
        if (locations.isEmpty()) return null;
        
        // Batch fetch restaurant details from cache
        return fetchRestaurantDetails(locations);
    }
    
    private List<RestaurantSummary> queryPostGISReplica(double lat, double lng, double radiusKm) {
        // Use read-replica routing
        return RoutingTemplate.execute("replica1", () -> {
            return restaurantRepository.findNearbyWithDistance(lat, lng, radiusKm)
                .stream()
                .map(row -> new RestaurantSummary(
                    (Long) row[0],   -- id
                    (String) row[1], -- name
                    (Double) row[2], -- lat
                    (Double) row[3], -- lng
                    ((BigDecimal) row[4]).doubleValue() / 1000.0  -- distance_m to km
                ))
                .toList();
        });
    }
}
```

### 2.5 Performance Validation

| Metric | Target | Measurement Method |
|--------|--------|-------------------|
| Nearby query p95 | < 50ms | Gatling with 20K concurrent users |
| Redis hit ratio | > 90% | `redis-cli info stats` |
| PostGIS index usage | GiST scan | `EXPLAIN ANALYZE` |
| Cache warm-up time | < 5min for hot geohashes | Load test with city-level traffic |
| Failover time | < 2s | Chaos test: kill Redis master |

---

## 3. Phase 2: Geospatial Social Feed — 50K+ TPS Optimization

**Goal**: Post wall fetch at 50,000+ TPS with sub-100ms p95 latency.

### 3.1 Data Model — Optimized for Scale

#### 3.1.1 PostgreSQL Schema with Partitioning

**File**: `services/social/src/main/resources/db/migration-pg/V20260916__create_social_schema_optimized.sql`

```sql
-- Posts: Hash partitioned by restaurant_id (16 partitions)
CREATE TABLE social_posts (
    id BIGSERIAL NOT NULL,
    restaurant_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    media_urls TEXT[] DEFAULT '{}',
    post_type VARCHAR(50) DEFAULT 'update',
    status VARCHAR(50) DEFAULT 'active',
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    like_count INT DEFAULT 0 NOT NULL,
    comment_count INT DEFAULT 0 NOT NULL,
    deleted_at TIMESTAMPTZ,
    -- Denormalized for query performance
    author_name VARCHAR(255),
    restaurant_name VARCHAR(255),
    restaurant_geog GEOGRAPHY(Point, 4326),  -- Denormalized for geo queries
    PRIMARY KEY (id, restaurant_id)
) PARTITION BY HASH (restaurant_id);

-- Create 32 partitions for higher write concurrency
DO $$
BEGIN
    FOR i IN 0..31 LOOP
        EXECUTE format('CREATE TABLE social_posts_p%s PARTITION OF social_posts '
                      || 'FOR VALUES WITH (MODULUS 32, REMAINDER %s);', i, i);
    END LOOP;
END $$;

-- Likes: Hash partitioned by post_id (32 partitions)
CREATE TABLE post_likes (
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    PRIMARY KEY (post_id, user_id)
) PARTITION BY HASH (post_id);

DO $$
BEGIN
    FOR i IN 0..31 LOOP
        EXECUTE format('CREATE TABLE post_likes_p%s PARTITION OF post_likes '
                      || 'FOR VALUES WITH (MODULUS 32, REMAINDER %s);', i, i);
    END LOOP;
END $$;

-- Comments: Hash partitioned by post_id (32 partitions)
CREATE TABLE post_comments (
    id BIGSERIAL NOT NULL,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    parent_comment_id BIGINT,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    PRIMARY KEY (id, post_id)
) PARTITION BY HASH (post_id);

DO $$
BEGIN
    FOR i IN 0..31 LOOP
        EXECUTE format('CREATE TABLE post_comments_p%s PARTITION OF post_comments '
                      || 'FOR VALUES WITH (MODULUS 32, REMAINDER %s);', i, i);
    END LOOP;
END $$;

-- Indexes (covering indexes for common queries)
CREATE INDEX CONCURRENTLY idx_social_posts_restaurant_created
    ON social_posts (restaurant_id, created_at DESC)
    INCLUDE (id, content, author_id, like_count, comment_count);

CREATE INDEX CONCURRENTLY idx_social_posts_author_created
    ON social_posts (author_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX CONCURRENTLY idx_post_likes_post_created
    ON post_likes (post_id, created_at DESC);

CREATE INDEX CONCURRENTLY idx_post_comments_post_created
    ON post_comments (post_id, created_at DESC)
    WHERE parent_comment_id IS NULL;  -- Top-level comments

-- PostGIS index on denormalized restaurant_geog
CREATE INDEX CONCURRENTLY idx_social_posts_geog
    ON social_posts USING GIST (restaurant_geog)
    WHERE status = 'active' AND deleted_at IS NULL;

-- Bloom index for post_id lookups (faster than btree for IN clauses)
CREATE INDEX CONCURRENTLY idx_post_likes_bloom
    ON post_likes USING BLOOM (post_id)
    WITH (bloom_columns = 1, bloom_page_ratio = 0.1);

ANALYZE social_posts;
ANALYZE post_likes;
ANALYZE post_comments;
```

#### 3.1.2 Redis Cluster Data Layout

**Sharding Strategy**: Use consistent hashing on geohash prefix for feed data, post_id for engagement data.

```java
public class RedisKeySchema {
    // Feed cache: sharded by geohash prefix (4 chars = ~20km)
    public static final String FEED_IDS = "feed:ids:%s:%d";  // geohash4, radiusBucket
    public static final String FEED_POSTS = "feed:posts:%s";  // postId
    public static final String FEED_META = "feed:meta:%s";    // geohash4:radius -> count
    
    // Engagement: sharded by post_id hash
    public static final String POST_LIKES = "post:likes:%d";         // postId -> count
    public static final String POST_LIKED_BY = "post:liked:%d:%d";  // postId, userId -> "1"
    public static final String POST_COMMENTS = "post:comments:%d"; // postId -> sorted set
    public static final String POST_DETAILS = "post:detail:%d";    // postId -> JSON
    
    // Bloom filters for post existence checks
    public static final String POST_BLOOM = "feed:bloom:%s";  // geohash4 -> bloom filter
    
    // Rate limiting (per-user, per-endpoint)
    public static final String RATE_LIMIT = "ratelimit:%s:%s";  // userId, endpoint
}
```

### 3.2 Pre-Computed Feed Segments (The 50K TPS Secret)

**Strategy**: Instead of computing feeds on-the-fly, pre-compute and cache feed segments by geohash + radius bucket.

#### 3.2.1 Feed Segment Builder

```java
@Component
@Slf4j
public class FeedSegmentBuilder {
    private final SocialPostRepository postRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    
    // Rebuild feed segment for a geohash cell every 30 seconds
    @Scheduled(fixedRateString = "${app.feed.segment-rebuild-ms:30000}")
    @SchedulerLock(name = "feedSegmentRebuild", lockAtMostFor = "25s")
    public void rebuildFeedSegments() {
        // 1. Get all active geohash cells (from Redis SET)
        Set<String> activeCells = redisTemplate.opsForSet()
            .members("feed:active:cells");
        
        for (String cell : activeCells) {
            rebuildSegment(cell);
        }
    }
    
    private void rebuildSegment(String geohash) {
        // 1. Decode geohash to bounding box
        GeoHashBounds bounds = GeohashUtils.decode(geohash);
        
        // 2. Query posts within this cell (PostGIS)
        List<SocialPost> posts = postRepository.findActivePostsInBounds(
            bounds.getSouthLat(), bounds.getWestLng(),
            bounds.getNorthLat(), bounds.getEastLng(),
            PageRequest.of(0, 200)  -- Top 200 posts per segment
        );
        
        // 3. Cache post IDs in Redis sorted set (score = created_at timestamp)
        String feedKey = String.format(RedisKeySchema.FEED_IDS, geohash, 5);  // 5km bucket
        redisTemplate.opsForZAdd().delete(feedKey);  -- Clear old
        Map<Double, String> postScores = posts.stream()
            .collect(Collectors.toMap(
                p -> p.getCreatedAt().atZone(ZoneOffset.UTC).toEpochSecond(),
                SocialPost::getId.toString()
            ));
        redisTemplate.opsForZAdd().add(feedKey, postScores);
        redisTemplate.expire(feedKey, 2, TimeUnit.HOURS);
        
        // 4. Cache post details (batch)
        cachePostDetails(posts);
        
        // 5. Update Bloom filter for existence check
        updateBloomFilter(geohash, posts);
    }
    
    private void updateBloomFilter(String geohash, List<SocialPost> posts) {
        String bloomKey = String.format(RedisKeySchema.POST_BLOOM, geohash);
        redisTemplate.opsForValue().set(bloomKey, 
            BloomFilter.build(posts.stream().map(SocialPost::getId).toList()),
            1, TimeUnit.HOURS);
    }
}
```

#### 3.2.2 Feed Fetch — The Hot Path

```java
@Service
@Slf4j
public class FeedService {
    private final RedisTemplate<String, String> redisTemplate;
    private final CaffeineCache l1Cache;
    private final RestaurantServiceClient restaurantClient;
    private final CounterManager counterManager;
    
    /**
     * Ultra-fast feed fetch targeting < 100ms p95 at 50K TPS.
     * 
     * Flow:
     * 1. L1 Cache (Caffeine) - ~1ms
     * 2. Redis Cluster (pre-computed feed segment) - ~5ms
     * 3. Parallel fetch of post details + restaurant metadata - ~10ms
     * 4. Merge and return
     */
    public FeedResponse getNearbyFeed(double lat, double lng, double radiusKm,
                                      String cursor, int size) {
        // 1. L1 Cache check
        String l1Key = "feed:l1:" + buildCacheKey(lat, lng, radiusKm, cursor);
        FeedResponse l1Response = (FeedResponse) l1Cache.getIfPresent(l1Key);
        if (l1Response != null) return l1Response;
        
        // 2. Determine geohash cells to query (multiple cells for large radius)
        List<String> geohashes = GeohashUtils.getCoveringCells(lat, lng, radiusKm, 4);
        
        // 3. Redis Cluster: fetch post IDs from multiple cells in parallel
        List<String> postIdStrings = Collections.synchronizedList(new ArrayList<>());
        geohashes.parallelStream().forEach(geohash -> {
            String feedKey = String.format(RedisKeySchema.FEED_IDS, geohash, radiusBucket(radiusKm));
            Set<String> ids = redisTemplate.opsForZSet()
                .reverseRange(feedKey, 0, size * 2L);  -- Fetch extra for filtering
            if (ids != null) postIdStrings.addAll(ids);
        });
        
        // 4. Bloom filter check (fast negative filter)
        Set<Long> postIds = postIdStrings.stream()
            .filter(id -> bloomFilterContains(geohashes.get(0), Long.parseLong(id)))
            .map(Long::parseLong)
            .limit(size)
            .toList();
        
        if (postIds.isEmpty()) {
            return FeedResponse.empty();
        }
        
        // 5. Parallel fetch: post details + restaurant metadata
        FeedResponse response = fetchFeedDetailsParallel(postIds, lat, lng, radiusKm);
        
        // 6. Cache in L1 (short TTL for freshness)
        l1Cache.put(l1Key, response);
        
        return response;
    }
    
    private FeedResponse fetchFeedDetailsParallel(List<Long> postIds, 
                                                  double lat, double lng, 
                                                  double radiusKm) {
        // Parallel stream for batch Redis get + DB fallback
        CompletableFuture<List<PostSummary>> postFuture = CompletableFuture.supplyAsync(() -> {
            return batchGetPostDetails(postIds);
        });
        
        CompletableFuture<List<RestaurantSummary>> restaurantFuture = CompletableFuture.supplyAsync(() -> {
            return restaurantClient.findNearby(lat, lng, radiusKm);
        });
        
        try {
            List<PostSummary> posts = postFuture.get(50, TimeUnit.MILLISECONDS);
            List<RestaurantSummary> restaurants = restaurantFuture.get(50, TimeUnit.MILLISECONDS);
            
            // Merge
            Map<Long, RestaurantSummary> restaurantMap = restaurants.stream()
                .collect(Collectors.toMap(RestaurantSummary::id, r -> r));
            
            return FeedResponse.of(
                posts.stream()
                    .map(p -> p.withRestaurant(restaurantMap.get(p.restaurantId())))
                    .toList()
            );
        } catch (TimeoutException e) {
            log.warn("Feed fetch timeout, returning partial results");
            return FeedResponse.partial(postFuture.getNow(null));
        }
    }
    
    private List<PostSummary> batchGetPostDetails(List<Long> postIds) {
        // MGET from Redis
        List<String> keys = postIds.stream()
            .map(id -> String.format(RedisKeySchema.FEED_POSTS, id))
            .toList();
        
        List<String> cachedPosts = redisTemplate.opsForValue().multiGet(keys);
        
        // Identify misses
        List<Long> missedIds = new ArrayList<>();
        List<PostSummary> results = new ArrayList<>();
        
        for (int i = 0; i < postIds.size(); i++) {
            if (cachedPosts.get(i) != null) {
                results.add(JsonUtils.fromJson(cachedPosts.get(i), PostSummary.class));
            } else {
                missedIds.add(postIds.get(i));
            }
        }
        
        // Batch load misses from DB (read replica)
        if (!missedIds.isEmpty()) {
            List<PostSummary> dbPosts = postRepository.findByIds(missedIds)
                .stream()
                .map(PostSummary::from)
                .toList();
            results.addAll(dbPosts);
            
            // Cache misses
            cachePostDetails(dbPosts);
        }
        
        return results;
    }
}
```

### 3.3 Like/Unlike — 100K+ TPS with Zero DB Contention

**Strategy**: Pure Redis hot path with async write-behind and conflict-free replication.

#### 3.3.1 Optimized Like Service

```java
@Service
@Slf4j
public class LikeService {
    private final RedisTemplate<String, String> redisTemplate;
    private final PostRepository postRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    
    private static final String LIKE_COUNT_KEY = "post:likes:%d";
    private static final String LIKED_BY_KEY = "post:liked:%d:%d";  // postId, userId
    private static final String LIKE_QUEUE_KEY = "likes:queue";  // For batch DB sync
    
    /**
     * Ultra-fast like/unlike with Redis pipeline.
     * Target: < 5ms p99 at 100K TPS.
     */
    public LikeResult toggleLike(Long postId, Long userId) {
        String countKey = String.format(LIKE_COUNT_KEY, postId);
        String likedByKey = String.format(LIKED_BY_KEY, postId, userId);
        
        // 1. Check current state (pipeline)
        Boolean alreadyLiked = redisTemplate.opsForValue().get(likedByKey) != null;
        
        // 2. Execute atomic operations
        if (alreadyLiked) {
            // Unlike: DEL + DECR
            redisTemplate.opsForValue().set(likedByKey, null, 0);  -- Immediate delete
            Long newCount = redisTemplate.opsForValue().increment(countKey, -1);
            enqueueLikeSync(postId, userId, false);
            return LikeResult.unliked(postId, newCount);
        } else {
            // Like: SETEX + INCR
            redisTemplate.opsForValue().set(likedByKey, "1", 7, TimeUnit.DAYS);
            Long newCount = redisTemplate.opsForValue().increment(countKey, 1);
            enqueueLikeSync(postId, userId, true);
            return LikeResult.liked(postId, newCount);
        }
    }
    
    /**
     * Enqueue like sync for batch processing.
     * Uses Redis List as queue, processed every 100ms or 1000 items.
     */
    private void enqueueLikeSync(Long postId, Long userId, boolean liked) {
        String queueItem = JsonUtils.toJson(Map.of(
            "postId", postId,
            "userId", userId,
            "liked", liked,
            "timestamp", Instant.now().toEpochMilli()
        ));
        
        redisTemplate.opsForList().leftPush(LIKE_QUEUE_KEY, queueItem);
        redisTemplate.expire(LIKE_QUEUE_KEY, 1, TimeUnit.HOURS);
    }
    
    /**
     * Batch sync likes to PostgreSQL (runs every 100ms).
     */
    @Scheduled(fixedRateString = "${app.like.sync-interval-ms:100}")
    @SchedulerLock(name = "likeBatchSync")
    public void batchSyncLikes() {
        List<String> items = redisTemplate.opsForList()
            .range(LIKE_QUEUE_KEY, 0, 999);  -- Max 1000 per batch
        
        if (items == null || items.isEmpty()) return;
        
        // Batch upsert to PostgreSQL
        jdbcTemplate.batchUpdate(
            "INSERT INTO post_likes (post_id, user_id, created_at) VALUES (?, ?, ?) "
          + "ON CONFLICT (post_id, user_id) DO NOTHING",
            new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    Map<String, Object> item = JsonUtils.fromJson(items.get(i), Map.class);
                    ps.setLong(1, (Long) item.get("postId"));
                    ps.setLong(2, (Long) item.get("userId"));
                    ps.setTimestamp(3, Timestamp.from(
                        Instant.ofEpochMilli((Long) item.get("timestamp"))
                    ));
                }
                @Override
                public int getBatchSize() {
                    return items.size();
                }
            }
        );
        
        // Remove processed items
        redisTemplate.opsForList().trim(LIKE_QUEUE_KEY, items.size(), -1);
    }
}
```

#### 3.3.2 Like Counter with Redis Cluster

```java
@Component
public class LikeCounterManager {
    private final RedisTemplate<String, String> redisTemplate;
    
    // Use Redis Hash for per-post counters (memory efficient)
    private static final String LIKE_COUNTERS_HASH = "post:like:counters";
    
    public Long incrementLike(Long postId) {
        return redisTemplate.opsForHash().increment(LIKE_COUNTERS_HASH, 
                                                     postId.toString(), 1);
    }
    
    public Long decrementLike(Long postId) {
        return redisTemplate.opsForHash().increment(LIKE_COUNTERS_HASH, 
                                                     postId.toString(), -1);
    }
    
    public Long getLikeCount(Long postId) {
        Object count = redisTemplate.opsForHash().get(LIKE_COUNTERS_HASH, postId.toString());
        return count != null ? Long.parseLong(count.toString()) : 0L;
    }
}
```

### 3.4 Feed Invalidation — Event-Driven

**File**: `services/social/src/main/java/com/bhukkad/social/event/FeedInvalidationHandler.java`

```java
@Component
@Slf4j
public class FeedInvalidationHandler {
    private final RedisTemplate<String, String> redisTemplate;
    private final CaffeineCache l1Cache;
    
    /**
     * Invalidate feed caches when new post is created.
     * Uses pub/sub for distributed invalidation across instances.
     */
    @KafkaListener(topics = "social-events", groupId = "social-invalidation")
    public void onPostCreated(PostCreatedEvent event) {
        // 1. Calculate affected geohash cells (current cell + neighbors)
        String geohash = GeohashUtils.encode(event.latitude(), event.longitude(), 4);
        List<String> affectedCells = GeohashUtils.getNeighbors(geohash);
        
        // 2. Publish invalidation message to Redis Pub/Sub
        affectedCells.forEach(cell -> {
            redisTemplate.convertAndSend("feed:invalidation", cell);
        });
        
        // 3. Local L1 cache invalidation
        l1Cache.asMap().keySet().removeIf(key -> 
            key.toString().contains(geohash)
        );
    }
    
    @RedisListener(channels = "feed:invalidation")
    public void handleInvalidation(String geohash) {
        // Invalidate Redis cache for this geohash
        Set<String> keys = redisTemplate.keys("feed:*:" + geohash + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.unlink(keys.toArray(new String[0]));  -- Async delete
        }
    }
}
```

### 3.5 Connection & Thread Pool Optimization

**Thread Pool Configuration**:
```java
@Configuration
@EnableAsync
public class AsyncConfig {
    
    @Bean("feedExecutor")
    public ThreadPoolTaskExecutor feedExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(100);          -- Base threads
        executor.setMaxPoolSize(500);           -- Burst capacity
        executor.setQueueCapacity(10000);       -- Buffer for spikes
        executor.setThreadNamePrefix("feed-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
    
    @Bean("likeExecutor")
    public ThreadPoolTaskExecutor likeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(50);
        executor.setMaxPoolSize(200);
        executor.setQueueCapacity(5000);
        executor.setThreadNamePrefix("like-");
        executor.initialize();
        return executor;
    }
}
```

**HTTP Client Optimization** (Feign/WebClient):
```yaml
feign:
  client:
    config:
      default:
        connectTimeout: 500
        readTimeout: 1000
        loggerLevel: BASIC
  httpclient:
    enabled: true
    max-connections: 500
    max-connections-per-route: 100
    time-to-live: 60
    connection-timeout: 500
```

### 3.6 Test Suite — 50K TPS Validation

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SocialFeedPerformanceTest {
    
    @Test
    void feed_fetch_50k_tps() throws Exception {
        // Gatling simulation:
        // - 50,000 concurrent users
        // - Each user requests nearby feed 10 times
        // - Total: 500K requests
        
        // Expected results:
        // - p95: < 100ms
        // - p99: < 200ms
        // - Error rate: < 0.1%
        // - Redis hit ratio: > 95%
    }
    
    @Test
    void like_toggle_100k_tps() throws Exception {
        // Gatling simulation:
        // - 100,000 concurrent likes on hot post
        
        // Expected results:
        // - p95: < 5ms
        // - p99: < 10ms
        // - Error rate: < 0.01%
    }
    
    @Test
    void feed_cache_warmup_performance() {
        // Cold start: feed cache empty
        // Measure time to warm up hot geohashes (top 100 cells)
        // Expected: < 5 minutes for 95% cache coverage
    }
    
    @Test
    void feed_failover_redis_down() {
        // Simulate Redis cluster failure
        // Expected: Feed still works via PostGIS, p95 < 200ms
    }
    
    @Test
    void feed_failover_postgis_down() {
        // Simulate PostGIS replica failure
        // Expected: Feed still works via Redis cache, p95 < 50ms
    }
}
```

### 3.7 Performance Targets & SLOs

| Metric | Target | Alert Threshold |
|--------|--------|-----------------|
| Feed fetch p95 | < 100ms | > 150ms |
| Feed fetch p99 | < 200ms | > 300ms |
| Feed TPS | > 50,000 | < 40,000 |
| Like toggle p95 | < 5ms | > 10ms |
| Like toggle TPS | > 100,000 | < 80,000 |
| Redis hit ratio | > 95% | < 90% |
| PostGIS query p95 | < 20ms | > 50ms |
| Cache warm-up time | < 5min | > 10min |

---

## 4. Phase 3: Contextual In-Post Ordering — High-Throughput Pipeline

**Goal**: Order creation from posts at 10K+ TPS with < 200ms p95 latency.

### 4.1 Architecture — CQRS + Event Sourcing

```
┌─────────────────────────────────────────────────────────────────────┐
│                     ORDER COMMAND SIDE (Write)                      │
│                                                                     │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────────────┐     │
│  │  Social     │───▶│   Order     │───▶│   PostgreSQL        │     │
│  │  Service    │    │   Service   │    │   (Primary)         │     │
│  │             │    │             │    │                     │     │
│  │ Validate    │    │ Saga        │    │ ┌─────────────────┐ │     │
│  │ Post + Menu │    │ Orchestrate │    │ │ orders          │ │     │
│  └─────────────┘    └─────────────┘    │ │ order_items     │ │     │
│         │                  │           │ │ order_saga      │ │     │
│         │                  │           │ └─────────────────┘ │     │
│         ▼                  ▼           └─────────────────────┘     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                    KAFKA (Outbox Events)                    │   │
│  │  order.created, payment.charged, kitchen.confirmed, ...    │   │
│  └───────────────────────────┬─────────────────────────────────┘   │
└──────────────────────────────┼─────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     ORDER QUERY SIDE (Read)                         │
│                                                                     │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────────────┐     │
│  │   Kafka     │───▶│   Redis     │    │   PostgreSQL        │     │
│  │  Consumer   │    │   Material  │    │   (Read Replicas)   │     │
│  │             │    │   View      │    │                     │     │
│  │ Project to  │    │             │    │ ┌─────────────────┐ │     │
│  │ read model │    │ OrderHash   │    │ │ order_views     │ │     │
│  └─────────────┘    │ (User)      │    │ │ user_orders     │ │     │
│                     │ OrderHash   │    │ │ restaurant_     │ │     │
│                     │ (Restaurant)│    │ │ orders          │ │     │
│                     └─────────────┘    └─────────────────────┘     │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.2 Order Command Optimization

**Key Strategies**:
1. **Saga with compensation** — non-blocking, async
2. **Command batching** — batch multiple orders in single DB transaction
3. **Connection pooling** — dedicated write pool
4. **Optimistic locking** — version columns to avoid deadlocks
5. **Outbox pattern** — atomic event publishing

#### 4.2.1 Order Command with Batching

```java
@Service
@Slf4j
public class SocialOrderService {
    private final OrderCommandRepository orderCommandRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RestaurantServiceClient restaurantClient;
    private final RedisTemplate<String, String> redisTemplate;
    
    private static final String ORDER_IDEMPOTENCY_KEY = "order:idempotency:%s:%s";  // userId, postId
    
    /**
     * Create order from post with high-throughput optimization.
     * Target: < 200ms p95 at 10K TPS.
     */
    @Transactional
    public OrderResponse createOrderFromPost(Long postId, String customerId, 
                                              SocialOrderRequest request) {
        // 1. Idempotency check (Redis, ~1ms)
        String idempotencyKey = String.format(ORDER_IDEMPOTENCY_KEY, customerId, postId);
        String existingOrderId = redisTemplate.opsForValue().get(idempotencyKey);
        if (existingOrderId != null) {
            return orderQueryService.getOrderSummary(Long.parseLong(existingOrderId));
        }
        
        // 2. Validate post + menu (parallel, ~20ms)
        CompletableFuture<Post> postFuture = CompletableFuture.supplyAsync(() -> 
            postService.getPost(postId));
        CompletableFuture<List<MenuItemValidation>> menuFuture = CompletableFuture.supplyAsync(() ->
            restaurantClient.validateMenuItems(
                request.items().stream().map(SocialOrderItem::menuItemId).toList()
            ));
        
        Post post = postFuture.get(30, TimeUnit.MILLISECONDS);
        List<MenuItemValidation> validations = menuFuture.get(30, TimeUnit.MILLISECONDS);
        
        // 3. Build command (no DB calls yet)
        OrderCreateCommand command = buildOrderCommand(post, request);
        
        // 4. Execute saga (async, fire-and-forget for main path)
        String orderId = CompletableFuture.supplyAsync(() ->
            orderCommandRepository.createOrder(command)
        ).get(100, TimeUnit.MILLISECONDS);  -- Timeout to prevent blocking
        
        // 5. Cache idempotency key
        redisTemplate.opsForValue().set(idempotencyKey, orderId, 24, TimeUnit.HOURS);
        
        // 6. Return immediately (order processing continues async)
        // Client can poll /api/v1/orders/{id} for status
        return OrderResponse.accepted(orderId);
    }
    
    private OrderCreateCommand buildOrderCommand(Post post, SocialOrderRequest request) {
        return OrderCreateCommand.builder()
            .customerId(request.customerId())
            .restaurantId(post.getRestaurantId())
            .sourcePostId(post.getId())
            .items(request.items().stream()
                .map(item -> OrderItemCommand.builder()
                    .menuItemId(item.menuItemId())
                    .quantity(item.quantity())
                    .modifiers(item.modifiers().stream()
                        .map(m -> ModifierCommand.builder()
                            .modifierId(m.modifierId())
                            .optionId(m.optionId())
                            .quantity(m.quantity())
                            .build())
                        .toList())
                    .specialInstructions(item.specialInstructions())
                    .build())
                .toList())
            .deliveryAddressId(request.deliveryAddressId())
            .paymentMethod(request.paymentMethod())
            .specialInstructions(request.specialInstructions())
            .build();
    }
}
```

### 4.3 Saga Optimization

**File**: `services/order/src/main/java/com/bhukkad/order/saga/OptimizedSagaCoordinator.java`

```java
@Component
@Slf4j
public class OptimizedSagaCoordinator {
    private final SagaInstanceRepository sagaRepository;
    private final OutboxClient outboxClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    
    private static final Set<String> IDEMPOTENT_STEPS = Set.of(
        "RESERVE_STOCK", "CHARGE_PAYMENT", "CONFIRM_ORDER"
    );
    
    /**
     * Optimized saga with parallel step execution where possible.
     */
    public void executeSaga(OrderCreateCommand command) {
        SagaInstance saga = sagaRepository.save(SagaInstance.start(command));
        
        // Parallel execution of independent steps
        CompletableFuture<SagaStep> reserveStock = CompletableFuture
            .supplyAsync(() -> executeStep(saga, "RESERVE_STOCK", command));
        CompletableFuture<SagaStep> validatePayment = CompletableFuture
            .supplyAsync(() -> executeStep(saga, "VALIDATE_PAYMENT", command));
        
        try {
            // Wait for both
            CompletableFuture.allOf(reserveStock, validatePayment).get(5, TimeUnit.SECONDS);
            
            // Dependent steps
            executeStep(saga, "CHARGE_PAYMENT", command);
            executeStep(saga, "CONFIRM_ORDER", command);
            
            // Publish OrderCreated event
            outboxClient.save("Order", saga.getOrderId(), "OrderCreated", 
                Map.of("orderId", saga.getOrderId(), "status", "CONFIRMED"));
            
        } catch (Exception e) {
            compensate(saga, e);
        }
    }
    
    private SagaStep executeStep(SagaInstance saga, String stepName, OrderCreateCommand command) {
        // Idempotency: skip if already completed
        if (saga.isStepCompleted(stepName)) {
            return saga.getStep(stepName);
        }
        
        // Execute step
        SagaStep step = switch (stepName) {
            case "RESERVE_STOCK" -> stockService.reserve(command.items());
            case "VALIDATE_PAYMENT" -> paymentService.validate(command.paymentMethod());
            case "CHARGE_PAYMENT" -> paymentService.charge(command.paymentMethod(), command.total());
            case "CONFIRM_ORDER" -> kitchenService.confirm(saga.getOrderId());
            default -> throw new IllegalArgumentException("Unknown step: " + stepName);
        };
        
        saga.recordStep(stepName, step);
        sagaRepository.save(saga);
        
        return step;
    }
}
```

### 4.4 Order Query — Materialized View Pattern

```java
@Component
public class OrderMaterializedView {
    private final RedisTemplate<String, String> redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    
    /**
     * Materialized view for fast order lookups.
     * Updated via Kafka consumer.
     */
    @KafkaListener(topics = "order-events", groupId = "order-query")
    public void onOrderEvent(OrderEvent event) {
        String key = String.format("order:user:%s:%s", event.userId(), event.orderId());
        
        OrderView view = OrderView.builder()
            .orderId(event.orderId())
            .userId(event.userId())
            .restaurantId(event.restaurantId())
            .status(event.status())
            .total(event.total())
            .createdAt(event.createdAt())
            .build();
        
        // Redis Hash for fast field access
        redisTemplate.opsForHash().putAll(key, view.toMap());
        redisTemplate.expire(key, 7, TimeUnit.DAYS);
        
        // Secondary index: user's recent orders
        redisTemplate.opsForZAdd().add(
            String.format("user:orders:%s", event.userId()),
            event.orderId().toString(),
            event.createdAt().toEpochMilli()
        );
    }
}
```

### 4.5 Database Connection Optimization

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 100        -- Write pool
      minimum-idle: 20
      connection-timeout: 500
      idle-timeout: 600000
      max-lifetime: 1800000
      leak-detection-threshold: 10000
      pool-name: "order-write-pool"
  
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 50             -- Batch inserts/updates
          order_inserts: true
          order_updates: true
        generate_statistics: false
```

### 4.6 Test Suite — 10K TPS Validation

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderPerformanceTest {
    
    @Test
    void order_from_post_10k_tps() throws Exception {
        // Gatling simulation:
        // - 10,000 concurrent orders from posts
        // - Each order: 2 menu items, 3 modifiers
        
        // Expected results:
        // - p95: < 200ms
        // - p99: < 500ms
        // - Error rate: < 0.5%
        // - Saga completion rate: > 99.5%
    }
    
    @Test
    void order_idempotency_prevents_duplicates() {
        // Double-tap: send same order twice within 1 second
        // Expected: Only one order created
    }
    
    @Test
    void order_saga_compensation_on_failure() {
        // Simulate payment failure
        // Expected: Stock released, order status = FAILED
    }
}
```

---

## 5. Phase 4: High-Throughput Search Engine — 5K+ QPS

**Goal**: Elasticsearch-powered search at 5,000+ QPS with < 50ms p95 latency.

### 5.1 Elasticsearch Cluster — Production Scale

#### 5.1.1 Cluster Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                    ELASTICSEARCH CLUSTER                            │
│                                                                     │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                │
│  │   Master 1  │  │   Master 2  │  │   Master 3  │                │
│  │  (Voting)   │  │  (Voting)   │  │  (Voting)   │                │
│  └─────────────┘  └─────────────┘  └─────────────┘                │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │              DATA HOT NODES (3 nodes)                        │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │   │
│  │  │   Data 1    │  │   Data 2    │  │   Data 3    │         │   │
│  │  │ 16GB RAM    │  │ 16GB RAM    │  │ 16GB RAM    │         │   │
│  │  │ 4 vCPU      │  │ 4 vCPU      │  │ 4 vCPU      │         │   │
│  │  │ Shards: 0,3 │  │ Shards: 1,4 │  │ Shards: 2,5 │         │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘         │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │               COORDINATING NODES (2 nodes)                   │   │
│  │  ┌─────────────┐  ┌─────────────┐                           │   │
│  │  │ Coord 1     │  │ Coord 2     │                           │   │
│  │  │ 8GB RAM     │  │ 8GB RAM     │                           │   │
│  │  │ 2 vCPU      │  │ 2 vCPU      │                           │   │
│  │  └─────────────┘  └─────────────┘                           │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘

Index Settings:
  - restaurants: 3 shards, 1 replica, refresh_interval=5s
  - menu_items: 5 shards, 1 replica, refresh_interval=5s
```

#### 5.1.2 Index Settings for High Throughput

```json
{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 1,
    "refresh_interval": "5s",
    "translog.durability": "ASYNC",
    "translog.flush_threshold_size": "512mb",
    "index.merge.policy.max_merged_segment": "2gb",
    "index.merge.policy.segments_per_tier": "10",
    "index.codec": "best_compression",
    "analysis": {
      "analyzer": {
        "autocomplete_analyzer": {
          "tokenizer": "autocomplete_tokenizer",
          "filter": ["lowercase", "autocomplete_filter"]
        },
        "phonetic_analyzer": {
          "tokenizer": "standard",
          "filter": ["lowercase", "phonetic_filter"]
        }
      },
      "tokenizer": {
        "autocomplete_tokenizer": {
          "type": "edge_ngram",
          "min_gram": 2,
          "max_gram": 20,
          "token_chars": ["letter", "digit"]
        }
      },
      "filter": {
        "autocomplete_filter": {
          "type": "edge_ngram",
          "min_gram": 2,
          "max_gram": 20
        },
        "phonetic_filter": {
          "type": "phonetic",
          "encoder": "metaphone",
          "replace": false
        }
      }
    }
  },
  "mappings": {
    "properties": {
      "id": { "type": "long" },
      "name": {
        "type": "text",
        "analyzer": "autocomplete_analyzer",
        "search_analyzer": "standard",
        "fields": {
          "keyword": { "type": "keyword" },
          "phonetic": {
            "type": "text",
            "analyzer": "phonetic_analyzer",
            "search_analyzer": "standard"
          }
        }
      },
      "cuisineSummary": {
        "type": "text",
        "analyzer": "autocomplete_analyzer",
        "fields": {
          "keyword": { "type": "keyword" }
        }
      },
      "location": {
        "type": "geo_point",
        "ignore_malformed": true
      },
      "averageRating": { "type": "float" },
      "deliveryRadiusKm": { "type": "integer" },
      "isActive": { "type": "boolean" },
      "priceRange": { "type": "keyword" },
      "tags": { "type": "keyword" },
      "popularityScore": { "type": "float" }  -- For ranking
    }
  }
}
```

### 5.2 Search Optimization Strategies

#### 5.2.1 Multi-Level Caching

```java
@Service
@Slf4j
public class OptimizedSearchService {
    private final ElasticsearchClient esClient;
    private final CaffeineCache l1Cache;        -- 1min TTL, 5K entries
    private final RedisTemplate<String, String> redisTemplate;  -- L2, 5min TTL
    private final JdbcTemplate jdbcTemplate;    -- L3 fallback
    
    private static final String SEARCH_L1_PREFIX = "search:l1:";
    private static final String SEARCH_L2_PREFIX = "search:l2:";
    
    /**
     * Optimized search with 3-tier caching.
     * Target: < 50ms p95 at 5K QPS.
     */
    public SearchResponse search(SearchRequest request) {
        String cacheKey = buildCacheKey(request);
        
        // 1. L1 Cache (Caffeine) - ~0.5ms
        SearchResponse l1 = (SearchResponse) l1Cache.getIfPresent(cacheKey);
        if (l1 != null) return l1;
        
        // 2. L2 Cache (Redis) - ~2ms
        String l2Cached = redisTemplate.opsForValue().get(SEARCH_L2_PREFIX + cacheKey);
        if (l2Cached != null) {
            SearchResponse l2 = JsonUtils.fromJson(l2Cached, SearchResponse.class);
            l1Cache.put(cacheKey, l2);
            return l2;
        }
        
        // 3. Elasticsearch - ~20ms
        SearchResponse esResponse = tryElasticsearchSearch(request);
        
        // 4. Fallback to PostgreSQL - ~100ms
        if (esResponse == null || esResponse.results().isEmpty()) {
            esResponse = fallbackToPostgres(request);
        }
        
        // 5. Cache results
        l1Cache.put(cacheKey, esResponse);
        redisTemplate.opsForValue().set(SEARCH_L2_PREFIX + cacheKey, 
            JsonUtils.toJson(esResponse), 5, TimeUnit.MINUTES);
        
        return esResponse;
    }
    
    private SearchResponse tryElasticsearchSearch(SearchRequest request) {
        try {
            // Build optimized query
            BoolQuery.Builder boolQuery = new BoolQuery.Builder();
            
            // Use match_phrase_prefix for autocomplete behavior
            if (request.query() != null && !request.query().isBlank()) {
                boolQuery.must(m -> m
                    .multiMatch(mm -> mm
                        .fields("name^3", "cuisineSummary^2", "description")
                        .query(request.query())
                        .type(MultiMatchQuery.Type.BEST_FIELDS)
                        .fuzziness("AUTO")
                        .prefixLength(2)
                        .boost(2.0f)
                    )
                );
            }
            
            // Geo filter with distance boost
            if (request.lat() != null && request.lng() != null && request.radiusKm() != null) {
                boolQuery.filter(f -> f
                    .geoDistance(g -> g
                        .field("location")
                        .location(GeoLocation.from(request.lat(), request.lng()))
                        .distance(request.radiusKm() + "km")
                        .boost(1.5f)
                    )
                );
            }
            
            // Active only
            boolQuery.filter(f -> f.term(t -> t.field("isActive").value(true)));
            
            // Execute search
            return esClient.search(s -> s
                .index("restaurants")
                .query(q -> q.bool(boolQuery.build().toQuery()))
                .source(src -> src
                    .includes(
                        "id", "name", "cuisineSummary", "averageRating",
                        "deliveryRadiusKm", "tags", "location"
                    )
                )
                .from(request.offset())
                .size(request.size())
                .trackTotalHits(false)  -- Performance optimization
                .sort(st -> st
                    .score(SortOrder.DESC)
                )
                .timeout(new TimeValue(30, TimeUnit.MILLISECONDS)),  -- Fail fast
                RestaurantDocument.class
            ).hits().hits().stream()
                .map(hit -> {
                    RestaurantDocument doc = hit.source();
                    return SearchResult.from(doc, hit.score());
                })
                .toList();
            
        } catch (ElasticsearchException e) {
            log.warn("ES search failed, falling back to PostgreSQL", e);
            return null;
        }
    }
}
```

#### 5.2.2 Bulk Indexing Optimization

```java
@Component
public class OptimizedSearchIndexer {
    private final ElasticsearchClient esClient;
    private final KafkaConsumer<ConsumerRecord<String, String>> consumer;
    
    private static final int BATCH_SIZE = 5000;
    private static final long FLUSH_INTERVAL_MS = 1000;
    
    /**
     * Batch index with async flushing.
     */
    public void indexRestaurants(List<RestaurantDocument> documents) {
        // Bulk request
        BulkRequest.Builder bulkRequest = new BulkRequest.Builder();
        
        for (RestaurantDocument doc : documents) {
            bulkRequest.operations(op -> op
                .index(idx -> idx
                    .index("restaurants")
                    .id(String.valueOf(doc.id()))
                    .document(doc)
                )
            );
        }
        
        // Execute with timeout
        try {
            BulkResponse response = esClient.bulk(bulkRequest.build());
            if (response.errors()) {
                log.error("Bulk indexing errors: {}", response.items().stream()
                    .filter(BulkResponseItem::isError)
                    .count());
            }
        } catch (IOException e) {
            log.error("Bulk indexing failed", e);
        }
    }
}
```

### 5.3 Synchronization — Exactly-Once

**File**: `services/search/src/main/java/com/bhukkad/search/kafka/SearchEventConsumer.java`

```java
@KafkaListener(
    topics = {"restaurant-events", "menu-events"},
    groupId = "search-service",
    concurrency = "6"  -- 6 threads for parallel processing
)
@Slf4j
public class SearchEventConsumer {
    private final ElasticsearchClient esClient;
    private final ObjectMapper objectMapper;
    private final BlockingQueue<IndexRequest> bulkQueue;
    private final ScheduledExecutorService flushExecutor;
    
    public SearchEventConsumer(ElasticsearchClient esClient, ObjectMapper objectMapper) {
        this.esClient = esClient;
        this.objectMapper = objectMapper;
        this.bulkQueue = new LinkedBlockingQueue<>(10000);
        this.flushExecutor = Executors.newScheduledThreadPool(2);
        
        // Flush every second or when batch full
        this.flushExecutor.scheduleAtFixedRate(this::flushBatch, 1000, 1000, TimeUnit.MILLISECONDS);
    }
    
    @KafkaHandler
    public void handleRestaurantEvent(ConsumerRecord<String, String> record) {
        try {
            JsonNode event = objectMapper.readTree(record.value());
            String eventType = event.get("eventType").asText();
            JsonNode payload = event.get("payload");
            
            IndexRequest request = switch (eventType) {
                case "RestaurantCreated", "RestaurantUpdated" -> buildIndexRequest(payload);
                case "RestaurantDeleted" -> buildDeleteRequest(payload);
                default -> null;
            };
            
            if (request != null) {
                bulkQueue.offer(request);
            }
            
            // Commit offset only after successful queue
            // (Kafka handles retry/DLQ for failures)
            
        } catch (Exception e) {
            log.error("Failed to process event", e);
            throw new RuntimeException(e);  -- Triggers retry
        }
    }
    
    private void flushBatch() {
        List<IndexRequest> batch = new ArrayList<>();
        bulkQueue.drainTo(batch, BATCH_SIZE);
        
        if (batch.isEmpty()) return;
        
        BulkRequest.Builder bulkRequest = new BulkRequest.Builder();
        batch.forEach(req -> bulkRequest.operations(op -> {
            // Add to bulk request
            return op;
        }));
        
        try {
            esClient.bulk(bulkRequest.build());
        } catch (IOException e) {
            log.error("Bulk flush failed", e);
            // Re-queue failed items
            batch.forEach(bulkQueue::offer);
        }
    }
}
```

### 5.4 Test Suite — 5K QPS Validation

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SearchPerformanceTest {
    
    @Test
    void search_5k_qps() throws Exception {
        // k6 simulation:
        // - 5,000 concurrent search requests
        // - Mix of queries: "pizza", "burger", "sushi", "indian"
        
        // Expected results:
        // - p95: < 50ms
        // - p99: < 100ms
        // - Error rate: < 0.1%
    }
    
    @Test
    void search_geo_filter_performance() {
        // Search with geo filter (5km radius)
        // Expected: < 30ms p95
    }
    
    @Test
    void search_phonetic_performance() {
        // Phonetic search (metaphone)
        // Expected: < 50ms p95
    }
    
    @Test
    void search_fallback_when_es_down() {
        // Stop ES, search should fallback to PostgreSQL
        // Expected: < 200ms p95 (slower but functional)
    }
}
```

---

## 6. Phase 5: Global Optimization Strategies

### 6.1 Database Optimization

#### 6.1.1 Connection Pooling at Scale

```yaml
# PgBouncer configuration (transaction pooling)
[databases]
social_db = host=postgres-primary port=5432 dbname=social_db
restaurant_db = host=postgres-primary port=5432 dbname=restaurant_db
order_db = host=postgres-primary port=5432 dbname=order_db

[pgbouncer]
listen_addr = 0.0.0.0
listen_port = 6432
auth_type = md5
auth_file = /etc/pgbouncer/userlist.txt
pool_mode = transaction
max_client_conn = 50000
default_pool_size = 100
reserve_pool_size = 20
max_db_connections = 500
max_user_connections = 200
log_connections = 1
log_disconnections = 1
log_pooler_errors = 1
```

#### 6.1.2 Partitioning Strategy

```sql
-- Time-based partitioning for social_posts (monthly)
CREATE TABLE social_posts_2026_09 PARTITION OF social_posts
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');

-- Auto-create partitions (trigger or cron)
-- Drop old partitions after 2 years (GDPR compliance)
```

### 6.2 Redis Cluster Optimization

#### 6.2.1 Memory Optimization

```yaml
redis:
  cluster:
    nodes: redis-1:6379,redis-2:6379,redis-3:6379
    max-redirects: 3
  lettuce:
    cluster:
      refresh-trigger:
        policy: adaptive
      adaptive-topology-refresh-timout: 30s
      topology-refresh-timeout: 5s
    pool:
      max-active: 200    -- Per instance
      max-idle: 50
      min-idle: 10
      time-between-eviction-runs: 30s
```

#### 6.2.2 Key Eviction Policy

```bash
# redis.conf
maxmemory 24gb
maxmemory-policy allkeys-lru  -- Evict least recently used
maxmemory-samples 5
```

### 6.3 Kafka Optimization

#### 6.3.1 Producer Optimization

```java
@Configuration
public class KafkaProducerConfig {
    
    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092");
        props.put(ProducerConfig.ACKS_CONFIG, "all");  -- Durability
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);  -- 16KB batches
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);  -- Wait 5ms for batching
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);  -- 32MB buffer
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");  -- Fast compression
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        
        return new DefaultKafkaProducerFactory<>(props);
    }
}
```

#### 6.3.2 Consumer Optimization

```java
@Configuration
public class KafkaConsumerConfig {
    
    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "search-service");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");  -- Manual commit
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);  -- Batch size
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 65536);  -- 64KB
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);
        props.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 1048576);  -- 1MB
        
        return new DefaultKafkaConsumerFactory<>(props);
    }
}
```

### 6.4 HTTP/2 & Connection Optimization

**Gateway Configuration**:
```yaml
spring:
  cloud:
    gateway:
      http2:
        enabled: true
      forward-x-headers: true
      httpclient:
        wiretap: false
      httpserver:
        wiretap: false
      routes:
        - id: social
          uri: lb://SOCIAL
          predicates:
            - Path=/api/v1/social/**
          filters:
            - name: CircuitBreaker
              args:
                name: social-cb
                fallbackUri: forward:/fallback/social
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 10000  -- 10K req/s
                redis-rate-limiter.burstCapacity: 20000
                redis-rate-limiter.requestedTokens: 1
```

### 6.5 JVM & GC Tuning

```bash
# JVM flags for 8GB heap
JAVA_OPTS="
  -Xms8g
  -Xmx8g
  -XX:+UseG1GC
  -XX:MaxGCPauseMillis=50
  -XX:+UnlockExperimentalVMOptions
  -XX:+UseStringDeduplication
  -XX:+UseCompressedOops
  -XX:+UseCompressedClassPointers
  -XX:+PrintFlagsFinal
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=/var/log/app/oom.hprof
"
```

### 6.6 Observability for Performance

```yaml
management:
  metrics:
    enable:
      lettuce: true
      kafka: true
      jdbc: true
    distribution:
      percentiles-histogram:
        "[http.server.requests]": true
      percentiles:
        "[http.server.requests]": 0.5, 0.95, 0.99
    tags:
      service: ${spring.application.name}
      environment: ${APP_ENV:production}
```

**Custom Metrics**:
```java
@Component
public class PerformanceMetrics {
    private final Counter feedRequests;
    private final Timer feedLatency;
    private final Counter likeRequests;
    private final Counter orderRequests;
    
    public PerformanceMetrics(MeterRegistry registry) {
        this.feedRequests = Counter.builder("social.feed.requests")
            .description("Total feed requests")
            .register(registry);
        this.feedLatency = Timer.builder("social.feed.latency")
            .description("Feed request latency")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
        this.likeRequests = Counter.builder("social.like.requests")
            .description("Total like requests")
            .register(registry);
        this.orderRequests = Counter.builder("order.requests")
            .description("Total order requests")
            .register(registry);
    }
}
```

---

## 7. Testing Strategy — High-Throughput Validation

### 7.1 Load Test Scenarios

| Scenario | Tool | Target TPS | Target p95 | Duration |
|----------|------|-----------|-----------|----------|
| **Post Wall Fetch** | Gatling | 50,000 | < 100ms | 30min |
| **Like Toggle** | Gatling | 100,000 | < 5ms | 30min |
| **Comment Create** | Gatling | 10,000 | < 50ms | 30min |
| **Order from Post** | Gatling | 10,000 | < 200ms | 30min |
| **Search** | k6 | 5,000 | < 50ms | 30min |
| **Mixed Workload** | Gatling | 75,000 | < 150ms | 60min |

### 7.2 Chaos Engineering Tests

| Failure Mode | Test | Expected Behavior |
|--------------|------|-------------------|
| **Redis Master Down** | Kill Redis master | Failover < 30s, feed works via DB |
| **Redis Cluster Partition** | Split-brain scenario | Reads succeed from reachable nodes |
| **PostGIS Replica Down** | Kill replica | Traffic routes to other replica, < 2s |
| **Kafka Broker Down** | Kill 1 broker | No data loss, consumer rebalances |
| **Elasticsearch Node Down** | Kill data node | Replica promoted, search continues |
| **Network Latency** | Add 100ms latency | Circuit breaker opens after 5 failures |
| **Database Connection Pool Exhaustion** | Flood with 10K connections | Requests queue, graceful degradation |

### 7.3 Performance Test Implementation (Gatling)

```scala
class SocialFeedSimulation extends Simulation {
    
  val feedScn = scenario("Post Wall Fetch")
    .exec(
      http("GET /api/v1/social/feed/nearby")
        .get("http://gateway:8080/api/v1/social/feed/nearby")
        .queryParam("lat", "12.9716")
        .queryParam("lng", "77.5946")
        .queryParam("radiusKm", "5")
        .header("Authorization", "Bearer ${customer_token}")
        .check(status.is(200))
    )
  
  setUp(
    feedScn.inject(
      rampUsersPerSec(1000) to (50000) during (300),  -- Ramp to 50K TPS over 5min
      constantUsersPerSec(50000) during (600)         -- Hold 50K TPS for 10min
    ).protocols(httpProtocol)
  ).protocols(httpProtocol)
    .assertions(
      global.responseTime.percentile3.lt(100),  -- p95 < 100ms
      global.responseTime.percentile4.lt(200),  -- p99 < 200ms
      global.successfulRequests.percent.gt(99.9) -- Error rate < 0.1%
    )
}
```

---

## 8. Strategy Planning & Timeline

### 8.1 Optimized Timeline (12 Weeks)

```
Week 1-2:  Phase 1 - Spatial Foundation (20K TPS baseline)
  ├── PostGIS migration + GiST index
  ├── Redis Cluster setup
  ├── Multi-level caching
  └── Benchmark: 20K TPS nearby queries

Week 3-4:  Phase 2a - Social Feed Core (50K TPS)
  ├── Social service bootstrap
  ├── Pre-computed feed segments
  ├── Like hot counters
  └── Benchmark: 50K TPS feed fetch

Week 5:     Phase 2b - Feed Optimization
  ├── L1/L2/L3 caching
  ├── Geohash sharding
  ├── Bloom filters
  └── Benchmark: 50K TPS with 95% cache hit ratio

Week 6-7:  Phase 3 - In-Post Ordering (10K TPS)
  ├── Social order adapter
  ├── Saga optimization
  ├── Materialized view
  └── Benchmark: 10K TPS orders

Week 8-9:  Phase 4 - Search Engine (5K QPS)
  ├── Elasticsearch cluster
  ├── Custom analyzers
  ├── Kafka sync
  └── Benchmark: 5K QPS search

Week 10:    Phase 5a - Scale & Harden
  ├── Circuit breakers
  ├── Connection pooling
  ├── JVM tuning
  └── Benchmark: Full load test

Week 11:    Phase 5b - Chaos Testing
  ├── Redis failure
  ├── ES failure
  ├── Kafka failure
  └── Chaos report

Week 12:    Production Rollout
  ├── Canary release (10% → 50% → 100%)
  ├── Monitoring dashboards
  └── Rollback plan
```

### 8.2 Resource Allocation

| Role | FTE | Focus |
|------|-----|-------|
| Backend Engineer (Performance) | 1 | Caching, indexing, query optimization |
| Backend Engineer (Social) | 1 | Feed service, likes, comments |
| Backend Engineer (Order) | 1 | Order optimization, saga tuning |
| Backend Engineer (Search) | 1 | Elasticsearch, Kafka sync |
| DevOps | 1 | Redis Cluster, ES cluster, Kafka, monitoring |
| QA Engineer (Performance) | 1 | Load tests, chaos tests, benchmarks |

### 8.3 Milestones with TPS Targets

| Milestone | Target TPS | p95 Target | Dependencies |
|-----------|-----------|-----------|--------------|
| PostGIS live | 20K | < 50ms | DB migration, Redis Cluster |
| Social feed MVP | 50K | < 100ms | PostGIS, Redis, pre-computed feeds |
| In-post ordering | 10K | < 200ms | Social feed, saga optimization |
| Search live | 5K QPS | < 50ms | ES cluster, Kafka sync |
| Full production | 75K mixed | < 150ms | All phases, chaos tested |

### 8.4 Risk Mitigation

| Risk | Probability | Impact | Mitigation |
|------|-----------|--------|-----------|
| Redis Cluster split-brain | Medium | High | Use Redis Cluster with proper timeout configuration; implement fallback to DB |
| PostGIS index bloat | Low | Medium | Use `CONCURRENTLY` for index creation; regular `VACUUM ANALYZE` |
| ES cluster instability | Medium | High | Start with 3 dedicated data nodes; monitor heap usage; set shard allocation awareness |
| Kafka backpressure | Low | Medium | Configure producer/consumer buffer sizes; implement flow control |
| Cache stampede | High | Medium | Use probabilistic early expiration + jittered TTL |
| Like count drift | Medium | Low | Accept eventual consistency (< 5s); reconciliation job for audit |

---

## 9. Operational Runbooks

### 9.1 Redis Cluster Setup

```bash
# 1. Create cluster (6 nodes: 3 masters, 3 replicas)
redis-cli --cluster create \
  redis-1:6379 redis-2:6379 redis-3:6379 \
  redis-4:6379 redis-5:6379 redis-6:6379 \
  --cluster-replicas 1

# 2. Verify cluster
redis-cli -c -p 6379 cluster info

# 3. Set memory policy
redis-cli CONFIG SET maxmemory 24gb
redis-cli CONFIG SET maxmemory-policy allkeys-lru

# 4. Enable slow log
redis-cli CONFIG SET slowlog-log-slower-than 10000  -- 10ms
redis-cli CONFIG SET slowlog-max-len 1024
```

### 9.2 Elasticsearch Cluster Setup

```bash
# 1. Configure master nodes (3 nodes)
# elasticsearch.yml:
cluster.name: bhukkad-search
node.roles: [ master ]
discovery.seed_hosts: ["master-1", "master-2", "master-3"]
cluster.initial_master_nodes: ["master-1", "master-2", "master-3"]

# 2. Configure data nodes (3 nodes)
# elasticsearch.yml:
node.roles: [ data_hot, data_warm ]
path.data: /var/lib/elasticsearch
xpack.ml.enabled: false  -- Disable ML to save memory

# 3. Create indices
curl -X PUT localhost:9200/restaurants -H "Content-Type: application/json" -d @restaurants.json
curl -X PUT localhost:9200/menu_items -H "Content-Type: application/json" -d @menu_items.json

# 4. Configure ILM
curl -X PUT localhost:9200/_ilm/policy/bhukkad-policy -H "Content-Type: application/json" -d '{
  "policy": {
    "phases": {
      "hot": {
        "actions": {
          "rollover": {
            "max_size": "50GB",
            "max_age": "30d"
          }
        }
      },
      "warm": {
        "min_age": "30d",
        "actions": {
          "allocate": {
            "require": { "data": "warm" }
          },
          "force_merge": { "max_num_segments": 1 }
        }
      }
    }
  }
}'

# 5. Verify cluster health
curl localhost:9200/_cluster/health?pretty
```

### 9.3 Database Performance Tuning

```sql
-- PostgreSQL configuration (postgresql.conf)
shared_buffers = 8GB
effective_cache_size = 24GB
maintenance_work_mem = 2GB
checkpoint_completion_target = 0.9
wal_buffers = 16MB
default_statistics_target = 100
random_page_cost = 1.1  -- SSD
effective_io_concurrency = 200
work_mem = 256MB
min_wal_size = 1GB
max_wal_size = 4GB

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Query optimization
ALTER SYSTEM SET max_connections = 500;
ALTER SYSTEM SET shared_buffers = '8GB';
ALTER SYSTEM SET effective_cache_size = '24GB';
SELECT pg_reload_conf();
```

---

## 10. Monitoring & Alerting

### 10.1 Key Metrics Dashboard

```yaml
# Prometheus recording rules
groups:
  - name: social_feed
    interval: 10s
    rules:
      - record: job:feed_requests:rate5m
        expr: sum(rate(social_feed_requests_total[5m])) by (service)
      
      - record: job:feed_latency:p95
        expr: histogram_quantile(0.95, sum(rate(social_feed_latency_seconds_bucket[5m])) by (le, service))
      
      - record: job:redis_hit_ratio
        expr: sum(rate(redis_keyspace_hits_total[5m])) / (sum(rate(redis_keyspace_hits_total[5m])) + sum(rate(redis_keyspace_misses_total[5m])))
  
  - name: order_service
    interval: 10s
    rules:
      - record: job:order_requests:rate5m
        expr: sum(rate(order_requests_total[5m])) by (service)
      
      - record: job:order_saga_duration:p95
        expr: histogram_quantile(0.95, sum(rate(order_saga_duration_seconds_bucket[5m])) by (le, service))
```

### 10.2 Alert Rules

```yaml
groups:
  - name: slo_alerts
    rules:
      - alert: HighFeedLatency
        expr: job:feed_latency:p95 > 0.1
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Feed latency p95 > 100ms"
      
      - alert: LowRedisHitRatio
        expr: job:redis_hit_ratio < 0.9
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Redis cache hit ratio < 90%"
      
      - alert: HighOrderErrorRate
        expr: rate(order_errors_total[5m]) / rate(order_requests_total[5m]) > 0.01
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Order error rate > 1%"
```

---

## 11. Conclusion

This redesigned implementation plan provides a **production-grade, 50K+ TPS-optimized architecture** for the bhukkad location-centric social commerce platform. Key optimizations include:

### 11.1 Post Wall Fetch Optimizations (50K+ TPS)

| Optimization | Impact |
|-------------|--------|
| **Pre-computed feed segments** | Eliminates on-the-fly computation; 95% cache hit ratio |
| **Multi-level caching (L1/L2/L3)** | p95 < 100ms even under load |
| **Redis Cluster with geohash sharding** | Horizontal scalability; no single point of failure |
| **Bloom filters** | Fast negative checks; reduces DB load by 80% |
| **Parallel fetching** | Hides latency; utilizes multi-core |
| **Connection pooling + PgBouncer** | Eliminates connection overhead |
| **Partitioned tables (32 partitions)** | Write scalability; partition pruning |

### 11.2 Order Optimizations (10K+ TPS)

| Optimization | Impact |
|-------------|--------|
| **CQRS + Materialized Views** | Read/write separation; 10x read performance |
| **Async saga execution** | Non-blocking; parallel step execution |
| **Idempotency caching** | Prevents duplicate orders; sub-millisecond check |
| **Batch DB operations** | Reduces round-trips; 50x batch inserts |
| **Outbox pattern** | Exactly-once event delivery |
| **Connection pool optimization** | Supports 10K concurrent orders |

### 11.3 Search Optimizations (5K+ QPS)

| Optimization | Impact |
|-------------|--------|
| **3-tier caching** | 95% queries served from cache |
| **ES index tuning** | 5s refresh; async translog; optimal shard count |
| **Bulk indexing** | 5K docs indexed in < 5s |
| **Kafka batching** | 5s flush interval; 5K batch size |
| **Coordinating nodes** | Dedicated query nodes; no data overhead |
| **Phonetic + edge n-gram** | Better relevance; no additional latency |

### 11.4 Global Strategies

| Strategy | Implementation |
|----------|---------------|
| **Read replicas** | 2 PostgreSQL replicas for read scaling |
| **Circuit breakers** | Resilience4j with 30% failure threshold |
| **Chaos testing** | Validate failover behavior before production |
| **Observability** | Metrics, tracing, structured logging |
| **Gradual rollout** | Canary releases; instant rollback capability |

Execute in 5 phases over 12 weeks with continuous performance validation. Each phase includes load testing to validate TPS targets before proceeding.
