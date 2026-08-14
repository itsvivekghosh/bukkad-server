-- Geo lookup and full-text search indexes

CREATE INDEX idx_address_lat_lon ON addresses (latitude, longitude);

ALTER TABLE restaurants
    ADD FULLTEXT INDEX ft_restaurant_name (name);

ALTER TABLE menu_items
    ADD FULLTEXT INDEX ft_menu_item_search (name, description);
