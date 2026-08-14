-- Element-collection table for Review.images (@ElementCollection)

CREATE TABLE IF NOT EXISTS review_images (
    review_id BIGINT NOT NULL,
    images VARCHAR(500) NOT NULL,
    PRIMARY KEY (review_id, images),
    CONSTRAINT fk_review_images_review
        FOREIGN KEY (review_id) REFERENCES reviews(id) ON DELETE CASCADE,
    INDEX idx_review_images_review (review_id)
);
