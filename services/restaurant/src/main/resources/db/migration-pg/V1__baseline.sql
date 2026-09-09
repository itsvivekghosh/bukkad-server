-- restaurant service schema - consolidated baseline (fresh start).
-- Derived from squashed platform baseline + service migrations.
--
-- PostgreSQL database dump
--


-- Dumped from database version 16.15
-- Dumped by pg_dump version 16.15




--
-- Name: campaign_usages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.campaign_usages (
    id bigint NOT NULL,
    campaign_id bigint NOT NULL,
    customer_id bigint NOT NULL,
    order_id bigint,
    used_at timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: campaign_usages_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.campaign_usages ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.campaign_usages_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: coupon_usages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.coupon_usages (
    id bigint NOT NULL,
    coupon_id bigint NOT NULL,
    customer_id bigint NOT NULL,
    order_id bigint,
    discount numeric(10,2) DEFAULT 0 NOT NULL,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: coupon_usages_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.coupon_usages ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.coupon_usages_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: coupons; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.coupons (
    id bigint NOT NULL,
    code character varying(40) NOT NULL,
    discount_type character varying(20) NOT NULL,
    discount_value numeric(10,2) NOT NULL,
    min_order_amount numeric(10,2) DEFAULT 0 NOT NULL,
    max_discount numeric(10,2),
    valid_from timestamp(6) without time zone,
    valid_until timestamp(6) without time zone,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: coupons_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.coupons ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.coupons_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: cuisines; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cuisines (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    image_url character varying(500),
    active boolean DEFAULT true NOT NULL
);


--
-- Name: cuisines_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.cuisines ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.cuisines_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: customization_choices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customization_choices (
    id bigint NOT NULL,
    menu_item_id bigint NOT NULL,
    name character varying(100) NOT NULL,
    is_required boolean DEFAULT false NOT NULL,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: customization_choices_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.customization_choices ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.customization_choices_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: customization_options; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customization_options (
    id bigint NOT NULL,
    choice_id bigint NOT NULL,
    label character varying(100) NOT NULL,
    price_delta numeric(10,2) DEFAULT 0 NOT NULL,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: customization_options_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.customization_options ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.customization_options_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: dead_letter_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dead_letter_events (
    id bigint NOT NULL,
    event_type character varying(80) NOT NULL,
    aggregate_type character varying(50) NOT NULL,
    aggregate_id bigint NOT NULL,
    payload text NOT NULL,
    last_error character varying(1000),
    retry_count integer DEFAULT 0 NOT NULL,
    source character varying(20),
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    requeued_at timestamp(6) without time zone
);


--
-- Name: dead_letter_events_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.dead_letter_events ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.dead_letter_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: dynamic_pricing_rules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dynamic_pricing_rules (
    id bigint NOT NULL,
    restaurant_id bigint NOT NULL,
    rule_name character varying(100) NOT NULL,
    multiplier numeric(5,2) NOT NULL,
    start_time time without time zone,
    end_time time without time zone,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: dynamic_pricing_rules_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.dynamic_pricing_rules ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.dynamic_pricing_rules_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: favorite_restaurants; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.favorite_restaurants (
    id bigint NOT NULL,
    customer_id bigint NOT NULL,
    restaurant_id bigint NOT NULL,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: idempotency_records; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.idempotency_records (
    id bigint NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    scope character varying(50) NOT NULL,
    owner_id bigint,
    status character varying(20) NOT NULL,
    response_payload text,
    created_at timestamp(6) without time zone NOT NULL,
    expires_at timestamp(6) without time zone NOT NULL
);


--
-- Name: idempotency_records_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.idempotency_records ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.idempotency_records_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: inventory_alerts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inventory_alerts (
    id bigint NOT NULL,
    menu_item_id bigint NOT NULL,
    threshold integer NOT NULL,
    current_stock integer NOT NULL,
    alert_type character varying(30) NOT NULL,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: inventory_alerts_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.inventory_alerts ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.inventory_alerts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: menu_categories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.menu_categories (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    description character varying(500),
    restaurant_id bigint NOT NULL,
    display_order integer DEFAULT 0,
    active boolean DEFAULT true NOT NULL
);


--
-- Name: menu_categories_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.menu_categories ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.menu_categories_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: menu_item_allergens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.menu_item_allergens (
    menu_item_id bigint NOT NULL,
    allergen character varying(100) NOT NULL
);


--
-- Name: menu_item_images; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.menu_item_images (
    menu_item_id bigint NOT NULL,
    image_url character varying(500) NOT NULL
);


--
-- Name: menu_item_ingredients; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.menu_item_ingredients (
    menu_item_id bigint NOT NULL,
    ingredient character varying(200) NOT NULL
);


--
-- Name: menu_item_ratings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.menu_item_ratings (
    id bigint NOT NULL,
    menu_item_id bigint NOT NULL,
    customer_id bigint NOT NULL,
    rating integer NOT NULL,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: menu_item_ratings_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.menu_item_ratings ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.menu_item_ratings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: menu_item_tags; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.menu_item_tags (
    menu_item_id bigint NOT NULL,
    tag character varying(100) NOT NULL
);


--
-- Name: menu_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.menu_items (
    id bigint NOT NULL,
    restaurant_id bigint NOT NULL,
    name character varying(200) NOT NULL,
    description text,
    price numeric(10,2) NOT NULL,
    is_available boolean DEFAULT true NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    search_vector tsvector GENERATED ALWAYS AS (to_tsvector('english'::regconfig, (((COALESCE(name, ''::character varying))::text || ' '::text) || COALESCE(description, ''::text)))) STORED,
    category_id bigint,
    original_price numeric(10,2),
    discount_percentage numeric(5,2),
    food_type character varying(20) DEFAULT 'VEG'::character varying NOT NULL,
    is_veg boolean DEFAULT true NOT NULL,
    is_spicy boolean DEFAULT false NOT NULL,
    spice_level character varying(20),
    image_url character varying(500),
    preparation_time integer,
    bestseller boolean DEFAULT false NOT NULL,
    recommended boolean DEFAULT false NOT NULL,
    calories integer,
    serving_size character varying(50),
    average_rating double precision DEFAULT 0.0 NOT NULL,
    total_ratings integer DEFAULT 0 NOT NULL,
    stock_quantity integer
);


--
-- Name: menu_items_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.menu_items ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.menu_items_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: menu_versions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.menu_versions (
    id bigint NOT NULL,
    restaurant_id bigint NOT NULL,
    version integer NOT NULL,
    snapshot_json text,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: menu_versions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.menu_versions ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.menu_versions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: outbox_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.outbox_events (
    id bigint NOT NULL,
    event_type character varying(80) NOT NULL,
    aggregate_type character varying(50) NOT NULL,
    aggregate_id bigint NOT NULL,
    payload text NOT NULL,
    status character varying(20) NOT NULL,
    retry_count integer DEFAULT 0 NOT NULL,
    last_error character varying(1000),
    created_at timestamp(6) without time zone NOT NULL,
    published_at timestamp(6) without time zone,
    processing_started_at timestamp(6) without time zone
);


--
-- Name: outbox_events_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.outbox_events ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.outbox_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: promo_banners; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.promo_banners (
    id bigint NOT NULL,
    title character varying(200) NOT NULL,
    image_url character varying(500),
    campaign_id bigint,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: promo_banners_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.promo_banners ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.promo_banners_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: promotion_campaigns; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.promotion_campaigns (
    id bigint NOT NULL,
    name character varying(120) NOT NULL,
    description text,
    discount_pct numeric(5,2) NOT NULL,
    max_discount numeric(10,2),
    starts_at timestamp(6) without time zone NOT NULL,
    ends_at timestamp(6) without time zone NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    campaign_type character varying(30) DEFAULT 'PERCENTAGE'::character varying NOT NULL,
    discount_percent double precision,
    min_order_amount double precision,
    restaurant_id bigint,
    max_discount_amount double precision,
    flat_discount_amount double precision,
    free_delivery boolean DEFAULT false NOT NULL,
    priority integer DEFAULT 0 NOT NULL,
    usage_limit integer,
    per_user_limit integer DEFAULT 1,
    buy_quantity integer,
    get_quantity integer,
    get_discount_percent double precision,
    target_segment character varying(30),
    applicable_menu_item_id bigint
);


--
-- Name: promotion_campaigns_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.promotion_campaigns ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.promotion_campaigns_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: restaurant_cuisines; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.restaurant_cuisines (
    restaurant_id bigint NOT NULL,
    cuisine_id bigint NOT NULL
);


--
-- Name: restaurant_features; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.restaurant_features (
    restaurant_id bigint NOT NULL,
    feature character varying(100) NOT NULL
);


--
-- Name: restaurant_food_types; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.restaurant_food_types (
    restaurant_id bigint NOT NULL,
    food_type character varying(20) NOT NULL
);


--
-- Name: restaurant_gallery; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.restaurant_gallery (
    restaurant_id bigint NOT NULL,
    image_url character varying(500) NOT NULL
);


--
-- Name: restaurant_ratings_summary; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.restaurant_ratings_summary (
    restaurant_id bigint NOT NULL,
    avg_rating double precision DEFAULT 0.0 NOT NULL,
    review_count integer DEFAULT 0 NOT NULL
);


--
-- Name: restaurants; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.restaurants (
    id bigint NOT NULL,
    name character varying(200) NOT NULL,
    description text,
    cuisine_id bigint NOT NULL,
    address text,
    phone character varying(20),
    is_active boolean DEFAULT true NOT NULL,
    avg_rating double precision DEFAULT 0.0 NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    search_vector tsvector GENERATED ALWAYS AS (to_tsvector('english'::regconfig, (((COALESCE(name, ''::character varying))::text || ' '::text) || COALESCE(description, ''::text)))) STORED,
    busy_mode boolean DEFAULT false NOT NULL,
    busy_until timestamp(6) without time zone,
    extra_prep_minutes integer DEFAULT 0 NOT NULL,
    image_url character varying(500),
    opening_time time without time zone DEFAULT '10:00:00'::time without time zone NOT NULL,
    closing_time time without time zone DEFAULT '23:00:00'::time without time zone NOT NULL,
    is_open boolean DEFAULT true NOT NULL,
    total_reviews integer DEFAULT 0 NOT NULL,
    average_delivery_time integer,
    minimum_order_amount double precision,
    delivery_fee double precision,
    free_delivery_available boolean DEFAULT false NOT NULL,
    free_delivery_above double precision,
    commission_percent double precision,
    is_pure_veg boolean DEFAULT false NOT NULL,
    license_number character varying(50),
    fssai_number character varying(50),
    onboarding_status character varying(30) DEFAULT 'APPROVED'::character varying NOT NULL,
    onboarding_rejection_reason character varying(255),
    virtual_brand_name character varying(100),
    tenant_id bigint
);


--
-- Name: restaurants_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.restaurants ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.restaurants_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: review_images; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.review_images (
    review_id bigint NOT NULL,
    image_url character varying(500) NOT NULL
);


--
-- Name: reviews; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.reviews (
    id bigint NOT NULL,
    restaurant_id bigint NOT NULL,
    customer_id bigint NOT NULL,
    rating integer NOT NULL,
    food_rating integer,
    delivery_rating integer,
    comment text,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    owner_response text,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL
);


--
-- Name: reviews_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.reviews ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.reviews_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: saga_instances; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.saga_instances (
    id bigint NOT NULL,
    saga_type character varying(50) NOT NULL,
    saga_id character varying(100) NOT NULL,
    current_step character varying(50),
    status character varying(20) NOT NULL,
    payload text,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL
);


--
-- Name: saga_instances_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.saga_instances ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.saga_instances_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: saga_steps; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.saga_steps (
    id bigint NOT NULL,
    saga_instance_id bigint NOT NULL,
    step_order integer NOT NULL,
    step_name character varying(50) NOT NULL,
    status character varying(20) NOT NULL,
    payload text,
    compensation_payload text,
    error_message character varying(1000),
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL
);


--
-- Name: saga_steps_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.saga_steps ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.saga_steps_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: tenants; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenants (
    id bigint NOT NULL,
    name character varying(120) NOT NULL,
    domain character varying(200) NOT NULL,
    brand_name character varying(200),
    logo_url character varying(500),
    theme_color character varying(20),
    currency character varying(3) DEFAULT 'INR'::character varying NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL
);


--
-- Name: tenants_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.tenants ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.tenants_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: campaign_usages campaign_usages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campaign_usages
    ADD CONSTRAINT campaign_usages_pkey PRIMARY KEY (id);


--
-- Name: coupon_usages coupon_usages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coupon_usages
    ADD CONSTRAINT coupon_usages_pkey PRIMARY KEY (id);


--
-- Name: coupons coupons_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coupons
    ADD CONSTRAINT coupons_pkey PRIMARY KEY (id);


--
-- Name: cuisines cuisines_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cuisines
    ADD CONSTRAINT cuisines_pkey PRIMARY KEY (id);


--
-- Name: customization_choices customization_choices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customization_choices
    ADD CONSTRAINT customization_choices_pkey PRIMARY KEY (id);


--
-- Name: customization_options customization_options_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customization_options
    ADD CONSTRAINT customization_options_pkey PRIMARY KEY (id);


--
-- Name: dead_letter_events dead_letter_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dead_letter_events
    ADD CONSTRAINT dead_letter_events_pkey PRIMARY KEY (id);


--
-- Name: dynamic_pricing_rules dynamic_pricing_rules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dynamic_pricing_rules
    ADD CONSTRAINT dynamic_pricing_rules_pkey PRIMARY KEY (id);


--
-- Name: idempotency_records idempotency_records_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_records
    ADD CONSTRAINT idempotency_records_pkey PRIMARY KEY (id);


--
-- Name: inventory_alerts inventory_alerts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_alerts
    ADD CONSTRAINT inventory_alerts_pkey PRIMARY KEY (id);


--
-- Name: menu_categories menu_categories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.menu_categories
    ADD CONSTRAINT menu_categories_pkey PRIMARY KEY (id);


--
-- Name: menu_item_ratings menu_item_ratings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.menu_item_ratings
    ADD CONSTRAINT menu_item_ratings_pkey PRIMARY KEY (id);


--
-- Name: menu_items menu_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.menu_items
    ADD CONSTRAINT menu_items_pkey PRIMARY KEY (id);


--
-- Name: menu_versions menu_versions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.menu_versions
    ADD CONSTRAINT menu_versions_pkey PRIMARY KEY (id);


--
-- Name: outbox_events outbox_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outbox_events
    ADD CONSTRAINT outbox_events_pkey PRIMARY KEY (id);


--
-- Name: promo_banners promo_banners_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.promo_banners
    ADD CONSTRAINT promo_banners_pkey PRIMARY KEY (id);


--
-- Name: promotion_campaigns promotion_campaigns_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.promotion_campaigns
    ADD CONSTRAINT promotion_campaigns_pkey PRIMARY KEY (id);


--
-- Name: restaurant_ratings_summary restaurant_ratings_summary_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.restaurant_ratings_summary
    ADD CONSTRAINT restaurant_ratings_summary_pkey PRIMARY KEY (restaurant_id);


--
-- Name: restaurants restaurants_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.restaurants
    ADD CONSTRAINT restaurants_pkey PRIMARY KEY (id);


--
-- Name: reviews reviews_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reviews
    ADD CONSTRAINT reviews_pkey PRIMARY KEY (id);


--
-- Name: saga_instances saga_instances_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saga_instances
    ADD CONSTRAINT saga_instances_pkey PRIMARY KEY (id);


--
-- Name: saga_steps saga_steps_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saga_steps
    ADD CONSTRAINT saga_steps_pkey PRIMARY KEY (id);


--
-- Name: tenants tenants_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenants
    ADD CONSTRAINT tenants_pkey PRIMARY KEY (id);


--
-- Name: coupons uk_coupon_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coupons
    ADD CONSTRAINT uk_coupon_code UNIQUE (code);


--
-- Name: coupon_usages uk_coupon_usage; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coupon_usages
    ADD CONSTRAINT uk_coupon_usage UNIQUE (coupon_id, customer_id, order_id);


--
-- Name: idempotency_records uk_idempotency_scope_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_records
    ADD CONSTRAINT uk_idempotency_scope_key UNIQUE (scope, idempotency_key);


--
-- Name: menu_item_ratings uk_menu_item_rating; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.menu_item_ratings
    ADD CONSTRAINT uk_menu_item_rating UNIQUE (menu_item_id, customer_id);


--
-- Name: saga_steps uq_saga_instance_step; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saga_steps
    ADD CONSTRAINT uq_saga_instance_step UNIQUE (saga_instance_id, step_order);


--
-- Name: saga_instances uq_saga_instances_saga_id; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saga_instances
    ADD CONSTRAINT uq_saga_instances_saga_id UNIQUE (saga_id);


--
-- Name: idx_category_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_category_active ON public.menu_categories USING btree (active);


--
-- Name: idx_category_restaurant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_category_restaurant ON public.menu_categories USING btree (restaurant_id);


--
-- Name: idx_category_restaurant_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_category_restaurant_active ON public.menu_categories USING btree (restaurant_id, active);


--
-- Name: idx_category_restaurant_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_category_restaurant_order ON public.menu_categories USING btree (restaurant_id, display_order);


--
-- Name: idx_coupon_usages_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_coupon_usages_customer ON public.coupon_usages USING btree (customer_id);


--
-- Name: idx_cuisine_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cuisine_name ON public.cuisines USING btree (name);


--
-- Name: idx_dlq_aggregate; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dlq_aggregate ON public.dead_letter_events USING btree (aggregate_type, aggregate_id);


--
-- Name: idx_dlq_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dlq_status_created ON public.dead_letter_events USING btree (status, created_at);


--
-- Name: idx_fav_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fav_customer ON public.favorite_restaurants USING btree (customer_id);


--
-- Name: idx_idempotency_expires; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_idempotency_expires ON public.idempotency_records USING btree (expires_at);


--
-- Name: idx_menu_item_bestseller; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_menu_item_bestseller ON public.menu_items USING btree (bestseller);


--
-- Name: idx_menu_item_category; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_menu_item_category ON public.menu_items USING btree (category_id);


--
-- Name: idx_menu_item_category_available; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_menu_item_category_available ON public.menu_items USING btree (category_id, is_available);


--
-- Name: idx_menu_item_food_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_menu_item_food_type ON public.menu_items USING btree (food_type);


--
-- Name: idx_menu_item_is_veg; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_menu_item_is_veg ON public.menu_items USING btree (is_veg);


--
-- Name: idx_menu_item_rating; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_menu_item_rating ON public.menu_items USING btree (average_rating);


--
-- Name: idx_menu_item_recommended; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_menu_item_recommended ON public.menu_items USING btree (recommended);


--
-- Name: idx_menu_item_search; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_menu_item_search ON public.menu_items USING gin (search_vector);


--
-- Name: idx_menu_restaurant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_menu_restaurant ON public.menu_items USING btree (restaurant_id, is_available);


--
-- Name: idx_menu_restaurant_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_menu_restaurant_name ON public.menu_items USING btree (restaurant_id, name);


--
-- Name: idx_menu_versions_restaurant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_menu_versions_restaurant ON public.menu_versions USING btree (restaurant_id, version);


--
-- Name: idx_outbox_aggregate; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_outbox_aggregate ON public.outbox_events USING btree (aggregate_type, aggregate_id);


--
-- Name: idx_outbox_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_outbox_status_created ON public.outbox_events USING btree (status, created_at);


--
-- Name: idx_promo_banners_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_promo_banners_active ON public.promo_banners USING btree (active);


--
-- Name: idx_promo_campaign_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_promo_campaign_active ON public.promotion_campaigns USING btree (active, starts_at, ends_at);


--
-- Name: idx_rc_cuisine; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rc_cuisine ON public.restaurant_cuisines USING btree (cuisine_id);


--
-- Name: idx_rc_restaurant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rc_restaurant ON public.restaurant_cuisines USING btree (restaurant_id);


--
-- Name: idx_restaurant_active_open; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_restaurant_active_open ON public.restaurants USING btree (is_active, is_open);


--
-- Name: idx_restaurant_active_rating; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_restaurant_active_rating ON public.restaurants USING btree (is_active, avg_rating);


--
-- Name: idx_restaurant_cuisine_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_restaurant_cuisine_active ON public.restaurants USING btree (cuisine_id, is_active);


--
-- Name: idx_restaurant_features_restaurant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_restaurant_features_restaurant ON public.restaurant_features USING btree (restaurant_id);


--
-- Name: idx_restaurant_food_types_restaurant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_restaurant_food_types_restaurant ON public.restaurant_food_types USING btree (restaurant_id);


--
-- Name: idx_restaurant_gallery_restaurant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_restaurant_gallery_restaurant ON public.restaurant_gallery USING btree (restaurant_id);


--
-- Name: idx_restaurant_min_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_restaurant_min_order ON public.restaurants USING btree (minimum_order_amount);


--
-- Name: idx_restaurant_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_restaurant_name ON public.restaurants USING btree (name);


--
-- Name: idx_restaurant_open; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_restaurant_open ON public.restaurants USING btree (is_open);


--
-- Name: idx_restaurant_pure_veg; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_restaurant_pure_veg ON public.restaurants USING btree (is_pure_veg);


--
-- Name: idx_restaurant_rating; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_restaurant_rating ON public.restaurants USING btree (avg_rating);


--
-- Name: idx_restaurant_search; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_restaurant_search ON public.restaurants USING gin (search_vector);


--
-- Name: idx_restaurants_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_restaurants_tenant ON public.restaurants USING btree (tenant_id);


--
-- Name: idx_review_images_review; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_review_images_review ON public.review_images USING btree (review_id);


--
-- Name: idx_reviews_restaurant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reviews_restaurant ON public.reviews USING btree (restaurant_id, created_at);


--
-- Name: idx_reviews_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reviews_status ON public.reviews USING btree (status);


--
-- Name: idx_saga_instance_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_saga_instance_status ON public.saga_steps USING btree (saga_instance_id, status);


--
-- Name: idx_saga_steps_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_saga_steps_pending ON public.saga_steps USING btree (status, step_order);


--
-- Name: idx_saga_type_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_saga_type_status ON public.saga_instances USING btree (saga_type, status);


--
-- Name: idx_tenant_domain; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_tenant_domain ON public.tenants USING btree (lower((domain)::text));


--
-- Name: idx_usage_campaign; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_usage_campaign ON public.campaign_usages USING btree (campaign_id);


--
-- Name: idx_usage_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_usage_customer ON public.campaign_usages USING btree (campaign_id, customer_id);


--
-- Name: campaign_usages campaign_usages_campaign_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campaign_usages
    ADD CONSTRAINT campaign_usages_campaign_id_fkey FOREIGN KEY (campaign_id) REFERENCES public.promotion_campaigns(id);


--
-- Name: coupon_usages coupon_usages_coupon_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coupon_usages
    ADD CONSTRAINT coupon_usages_coupon_id_fkey FOREIGN KEY (coupon_id) REFERENCES public.coupons(id);


--
-- Name: customization_choices customization_choices_menu_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customization_choices
    ADD CONSTRAINT customization_choices_menu_item_id_fkey FOREIGN KEY (menu_item_id) REFERENCES public.menu_items(id);


--
-- Name: customization_options customization_options_choice_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customization_options
    ADD CONSTRAINT customization_options_choice_id_fkey FOREIGN KEY (choice_id) REFERENCES public.customization_choices(id) ON DELETE CASCADE;


--
-- Name: dynamic_pricing_rules dynamic_pricing_rules_restaurant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dynamic_pricing_rules
    ADD CONSTRAINT dynamic_pricing_rules_restaurant_id_fkey FOREIGN KEY (restaurant_id) REFERENCES public.restaurants(id);


--
-- Name: saga_steps fk_saga_steps_instance; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saga_steps
    ADD CONSTRAINT fk_saga_steps_instance FOREIGN KEY (saga_instance_id) REFERENCES public.saga_instances(id) ON DELETE CASCADE;


--
-- Name: inventory_alerts inventory_alerts_menu_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_alerts
    ADD CONSTRAINT inventory_alerts_menu_item_id_fkey FOREIGN KEY (menu_item_id) REFERENCES public.menu_items(id);


--
-- Name: menu_categories menu_categories_restaurant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.menu_categories
    ADD CONSTRAINT menu_categories_restaurant_id_fkey FOREIGN KEY (restaurant_id) REFERENCES public.restaurants(id);


--
-- Name: menu_item_ratings menu_item_ratings_menu_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.menu_item_ratings
    ADD CONSTRAINT menu_item_ratings_menu_item_id_fkey FOREIGN KEY (menu_item_id) REFERENCES public.menu_items(id);


--
-- Name: menu_items menu_items_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.menu_items
    ADD CONSTRAINT menu_items_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.menu_categories(id);


--
-- Name: menu_items menu_items_restaurant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.menu_items
    ADD CONSTRAINT menu_items_restaurant_id_fkey FOREIGN KEY (restaurant_id) REFERENCES public.restaurants(id);


--
-- Name: menu_versions menu_versions_restaurant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.menu_versions
    ADD CONSTRAINT menu_versions_restaurant_id_fkey FOREIGN KEY (restaurant_id) REFERENCES public.restaurants(id);


--
-- Name: promo_banners promo_banners_campaign_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.promo_banners
    ADD CONSTRAINT promo_banners_campaign_id_fkey FOREIGN KEY (campaign_id) REFERENCES public.promotion_campaigns(id);


--
-- Name: restaurant_ratings_summary restaurant_ratings_summary_restaurant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.restaurant_ratings_summary
    ADD CONSTRAINT restaurant_ratings_summary_restaurant_id_fkey FOREIGN KEY (restaurant_id) REFERENCES public.restaurants(id);


--
-- Name: restaurants restaurants_cuisine_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.restaurants
    ADD CONSTRAINT restaurants_cuisine_id_fkey FOREIGN KEY (cuisine_id) REFERENCES public.cuisines(id);


--
-- Name: review_images review_images_review_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.review_images
    ADD CONSTRAINT review_images_review_id_fkey FOREIGN KEY (review_id) REFERENCES public.reviews(id) ON DELETE CASCADE;


--
-- Name: reviews reviews_restaurant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reviews
    ADD CONSTRAINT reviews_restaurant_id_fkey FOREIGN KEY (restaurant_id) REFERENCES public.restaurants(id);


--
-- PostgreSQL database dump complete
--


