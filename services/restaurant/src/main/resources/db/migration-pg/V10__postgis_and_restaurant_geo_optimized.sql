-- =============================================================================
-- Phase 1: Spatial Foundation — PostGIS optimization for the restaurant service
-- =============================================================================
-- This migration is best-effort: if the PostGIS extension is not available
-- in the PostgreSQL installation, the migration is a no-op and the service
-- falls back to the legacy Haversine in-memory filter.

DO $$
BEGIN
    -- Try to enable PostGIS; if not available, skip all spatial optimization.
    CREATE EXTENSION IF NOT EXISTS postgis;
EXCEPTION
    WHEN OTHERS THEN
        RAISE NOTICE 'PostGIS not available, skipping spatial optimization (V10)';
        RETURN;
END $$;

-- Only proceed if PostGIS is now available.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'postgis') THEN
        RAISE NOTICE 'PostGIS not available, skipping geog column and indexes';
        RETURN;
    END IF;

    -- Add geography column (WGS84). Pre-existing latitude/longitude columns
    -- are populated by V2; this migration backfills geog from them.
    ALTER TABLE public.restaurants
        ADD COLUMN IF NOT EXISTS geog GEOGRAPHY(Point, 4326);

    -- Backfill in a single pass. For test environments the table is small;
    -- production backfills should run as a separate admin job.
    UPDATE public.restaurants
    SET geog = ST_MakePoint(longitude, latitude)::GEOGRAPHY
    WHERE geog IS NULL
      AND latitude IS NOT NULL
      AND longitude IS NOT NULL;
END $$;

-- Indexes and analysis only if PostGIS is available.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'postgis') THEN
        -- GiST index for radius queries.
        CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_restaurants_geog
            ON public.restaurants USING GIST (geog);

        -- Covering index for the nearby query.
        CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_restaurants_nearby_covering
            ON public.restaurants USING GIST (geog)
            INCLUDE (id, name, latitude, longitude, is_active, delivery_radius_km, avg_rating);

        -- Partial index for active restaurants only.
        CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_restaurants_active_geog
            ON public.restaurants USING GIST (geog)
            WHERE is_active = true;

        -- BRIN index on created_at.
        CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_restaurants_created_brin
            ON public.restaurants USING BRIN (created_at);

        ANALYZE public.restaurants;
    END IF;
END $$;
