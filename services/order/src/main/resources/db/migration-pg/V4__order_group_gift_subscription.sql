-- Order service V4: group orders, gift cards, subscriptions (Priority 4)
CREATE TABLE group_orders (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    host_id     BIGINT       NOT NULL,
    restaurant_id BIGINT     NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMP(6) NOT NULL,
    updated_at  TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_group_orders_host ON group_orders (host_id);

CREATE TABLE group_order_members (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_order_id BIGINT       NOT NULL REFERENCES group_orders (id) ON DELETE CASCADE,
    customer_id    BIGINT       NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    created_at     TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_group_member UNIQUE (group_order_id, customer_id)
);

CREATE TABLE gift_cards (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(40)    NOT NULL,
    balance     NUMERIC(12,2)  NOT NULL,
    status      VARCHAR(20)    NOT NULL,
    created_at  TIMESTAMP(6)   NOT NULL,
    expires_at  TIMESTAMP(6),
    CONSTRAINT uk_gift_card_code UNIQUE (code)
);

CREATE TABLE subscriptions (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id BIGINT         NOT NULL,
    restaurant_id BIGINT       NOT NULL,
    plan        VARCHAR(50)    NOT NULL,
    status      VARCHAR(20)    NOT NULL,
    created_at  TIMESTAMP(6)   NOT NULL,
    updated_at  TIMESTAMP(6)   NOT NULL
);
CREATE INDEX idx_subscriptions_customer ON subscriptions (customer_id, status);