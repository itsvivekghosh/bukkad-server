-- Element-collection tables for Restaurant entity (@ElementCollection)

CREATE TABLE IF NOT EXISTS restaurant_features (
    restaurant_id BIGINT NOT NULL,
    feature VARCHAR(100) NOT NULL,
    PRIMARY KEY (restaurant_id, feature),
    CONSTRAINT fk_restaurant_features_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants(id) ON DELETE CASCADE,
    INDEX idx_restaurant_features_restaurant (restaurant_id)
);

CREATE TABLE IF NOT EXISTS restaurant_gallery (
    restaurant_id BIGINT NOT NULL,
    image_url VARCHAR(500) NOT NULL,
    PRIMARY KEY (restaurant_id, image_url),
    CONSTRAINT fk_restaurant_gallery_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants(id) ON DELETE CASCADE,
    INDEX idx_restaurant_gallery_restaurant (restaurant_id)
);

CREATE TABLE IF NOT EXISTS restaurant_food_types (
    restaurant_id BIGINT NOT NULL,
    food_type VARCHAR(50) NOT NULL,
    PRIMARY KEY (restaurant_id, food_type),
    CONSTRAINT fk_restaurant_food_types_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants(id) ON DELETE CASCADE,
    INDEX idx_restaurant_food_types_restaurant (restaurant_id)
);
