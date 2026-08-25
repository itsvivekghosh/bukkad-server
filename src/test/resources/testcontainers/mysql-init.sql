-- Grants the test user server-level CREATE privilege so V54
-- (per-domain schemas) can create the target databases. Without this
-- grant, the bhukkad user can only access bhukkad_test and V54 fails
-- with "Access denied for user 'bhukkad'@'%' to database 'bhukkad_orders'".
GRANT ALL PRIVILEGES ON *.* TO 'bhukkad'@'%';
FLUSH PRIVILEGES;