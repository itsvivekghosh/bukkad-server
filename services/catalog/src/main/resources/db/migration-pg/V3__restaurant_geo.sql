-- Geolocation for nearby-restaurant discovery (monolith parity).
ALTER TABLE public.restaurants
    ADD COLUMN IF NOT EXISTS latitude        double precision,
    ADD COLUMN IF NOT EXISTS longitude       double precision,
    ADD COLUMN IF NOT EXISTS is_pure_veg     boolean DEFAULT false NOT NULL,
    ADD COLUMN IF NOT EXISTS delivery_radius_km integer DEFAULT 5;
CREATE INDEX IF NOT EXISTS idx_restaurants_location
    ON public.restaurants (latitude, longitude);
ALTER TABLE public.restaurants ADD CONSTRAINT restaurants_location_in_range
    CHECK (latitude IS NULL OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180));
