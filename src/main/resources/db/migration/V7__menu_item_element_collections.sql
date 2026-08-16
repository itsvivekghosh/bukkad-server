-- Element-collection tables for MenuItem entity (@ElementCollection)

CREATE TABLE IF NOT EXISTS menu_item_tags (
    menu_item_id BIGINT NOT NULL,
    tag VARCHAR(100) NOT NULL,
    PRIMARY KEY (menu_item_id, tag),
    CONSTRAINT fk_menu_item_tags_item
        FOREIGN KEY (menu_item_id) REFERENCES menu_items(id) ON DELETE CASCADE,
    INDEX idx_menu_item_tags_item (menu_item_id)
);

CREATE TABLE IF NOT EXISTS menu_item_allergens (
    menu_item_id BIGINT NOT NULL,
    allergen VARCHAR(100) NOT NULL,
    PRIMARY KEY (menu_item_id, allergen),
    CONSTRAINT fk_menu_item_allergens_item
        FOREIGN KEY (menu_item_id) REFERENCES menu_items(id) ON DELETE CASCADE,
    INDEX idx_menu_item_allergens_item (menu_item_id)
);

CREATE TABLE IF NOT EXISTS menu_item_ingredients (
    menu_item_id BIGINT NOT NULL,
    ingredient VARCHAR(200) NOT NULL,
    PRIMARY KEY (menu_item_id, ingredient),
    CONSTRAINT fk_menu_item_ingredients_item
        FOREIGN KEY (menu_item_id) REFERENCES menu_items(id) ON DELETE CASCADE,
    INDEX idx_menu_item_ingredients_item (menu_item_id)
);

CREATE TABLE IF NOT EXISTS menu_item_images (
    menu_item_id BIGINT NOT NULL,
    image_url VARCHAR(500) NOT NULL,
    PRIMARY KEY (menu_item_id, image_url),
    CONSTRAINT fk_menu_item_images_item
        FOREIGN KEY (menu_item_id) REFERENCES menu_items(id) ON DELETE CASCADE,
    INDEX idx_menu_item_images_item (menu_item_id)
);
