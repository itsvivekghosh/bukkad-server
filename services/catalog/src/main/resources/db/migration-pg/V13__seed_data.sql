-- Seed data for local development and testing
-- Cuisines (only columns that exist in V1 baseline; id is GENERATED ALWAYS)
INSERT INTO cuisines (name, image_url, active)
VALUES
    ('Indian', null, true),
    ('Chinese', null, true),
    ('Italian', null, true),
    ('Mexican', null, true),
    ('Thai', null, true),
    ('Japanese', null, true),
    ('American', null, true),
    ('Mediterranean', null, true)
ON CONFLICT DO NOTHING;

-- Note: Restaurant, menu_categories, and menu_items seed data will be created
-- dynamically via the onboarding API or admin panel when restaurants are created.
-- This keeps the seed data minimal and avoids foreign key constraint issues.

-- Create a sample restaurant owner user for testing (placeholder - actual users created via identity service)
-- INSERT INTO users (id, email, password_hash, full_name, role, created_at, updated_at)
-- VALUES (100, 'owner@test.com', '$2a$10$...', 'Test Owner', 'RESTAURANT_OWNER', NOW(), NOW())
-- ON CONFLICT DO NOTHING;