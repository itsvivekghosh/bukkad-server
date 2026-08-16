-- V14: Delivery truth — smarter ETA history, zone surge rules, free-delivery tiers

CREATE TABLE order_eta_snapshots (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id                BIGINT        NOT NULL,
    eta_minutes             INT           NOT NULL,
    eta_at                  TIMESTAMP     NOT NULL,
    confidence_low_minutes  INT           NULL,
    confidence_high_minutes INT           NULL,
    traffic_factor          DOUBLE        NULL,
    surge_multiplier        DOUBLE        NULL,
    factors_summary         VARCHAR(500),
    recorded_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_eta_snapshot_order FOREIGN KEY (order_id) REFERENCES orders(id),
    INDEX idx_eta_snapshot_order (order_id, recorded_at DESC)
);

CREATE TABLE zone_surge_rules (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_id          BIGINT        NOT NULL,
    day_of_week      INT           NULL,
    start_hour       INT           NOT NULL,
    end_hour         INT           NOT NULL,
    surge_multiplier DOUBLE        NOT NULL DEFAULT 1.0,
    is_active        BOOLEAN       NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_surge_zone FOREIGN KEY (zone_id) REFERENCES delivery_zones(id),
    INDEX idx_surge_zone (zone_id, is_active)
);

ALTER TABLE delivery_zones
    ADD COLUMN free_delivery_above DOUBLE NULL;

-- Peak-hour surge for Bangalore Central (zone id 1 from V13 seed)
INSERT INTO zone_surge_rules (zone_id, day_of_week, start_hour, end_hour, surge_multiplier)
VALUES (1, NULL, 12, 14, 1.25),
       (1, NULL, 19, 22, 1.35);
