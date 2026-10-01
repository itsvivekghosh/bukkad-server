-- Social-order breadth fixes discovered by the API test suite:
--
-- 1. group_orders.restaurant_id: the host opens a group order BEFORE a
--    restaurant is picked (the share link + vote flow), so the column must be
--    nullable (monolith parity).
-- 2. subscription_plans: the self-service POST body may omit restaurant_id
--    for platform-wide plans.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'group_orders' AND column_name = 'restaurant_id'
                 AND is_nullable = 'NO') THEN
        ALTER TABLE public.group_orders
            ALTER COLUMN restaurant_id DROP NOT NULL;
    END IF;
END $$;
