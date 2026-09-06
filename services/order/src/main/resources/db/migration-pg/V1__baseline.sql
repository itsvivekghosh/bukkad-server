-- order service schema - consolidated baseline (fresh start).
-- Derived from squashed platform baseline + service migrations.
--
-- PostgreSQL database dump
--


-- Dumped from database version 16.15
-- Dumped by pg_dump version 16.15




--
-- Name: cart_item_customizations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cart_item_customizations (
    id bigint NOT NULL,
    cart_item_id bigint NOT NULL,
    customization_choice_id bigint NOT NULL
);


--
-- Name: cart_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cart_items (
    id bigint NOT NULL,
    cart_id bigint NOT NULL,
    menu_item_id bigint NOT NULL,
    item_name character varying(200) NOT NULL,
    unit_price numeric(10,2) NOT NULL,
    quantity integer NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    special_instructions text
);


--
-- Name: cart_items_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.cart_items ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.cart_items_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: carts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.carts (
    id bigint NOT NULL,
    customer_id bigint NOT NULL,
    status character varying(20) NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    restaurant_id bigint,
    coupon_code character varying(50)
);


--
-- Name: carts_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.carts ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.carts_id_seq
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
    used_at timestamp(6) without time zone NOT NULL
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
    description character varying(255) NOT NULL,
    discount_type character varying(20) NOT NULL,
    discount_value numeric(10,2) NOT NULL,
    minimum_order_amount numeric(10,2),
    maximum_discount_amount numeric(10,2),
    valid_from timestamp(6) without time zone NOT NULL,
    valid_until timestamp(6) without time zone NOT NULL,
    usage_limit integer,
    used_count integer DEFAULT 0 NOT NULL,
    per_user_limit integer,
    active boolean DEFAULT true NOT NULL,
    restaurant_id bigint
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
-- Name: customer_memberships; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customer_memberships (
    id bigint NOT NULL,
    customer_id bigint NOT NULL,
    plan_id bigint NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    starts_at timestamp without time zone NOT NULL,
    ends_at timestamp without time zone NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
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
-- Name: disputes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.disputes (
    id bigint NOT NULL,
    order_id bigint NOT NULL,
    type character varying(20) NOT NULL,
    status character varying(20) NOT NULL,
    customer_evidence text,
    rider_evidence text,
    restaurant_evidence text,
    resolution_notes text,
    resolution character varying(20),
    refund_amount numeric(12,2),
    resolved_by bigint,
    resolved_at timestamp(6) without time zone,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: disputes_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.disputes ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.disputes_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: gift_cards; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.gift_cards (
    id bigint NOT NULL,
    code character varying(40) NOT NULL,
    balance numeric(12,2) NOT NULL,
    status character varying(20) NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    expires_at timestamp(6) without time zone,
    amount numeric(12,2) DEFAULT 0.0 NOT NULL,
    purchased_by bigint,
    recipient_email character varying(100),
    recipient_name character varying(100),
    message text,
    redeemed_by bigint,
    redeemed_at timestamp(6) without time zone
);


--
-- Name: gift_cards_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.gift_cards ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.gift_cards_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: gift_orders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.gift_orders (
    id bigint NOT NULL,
    order_id bigint NOT NULL,
    sender_user_id bigint,
    recipient_name character varying(100),
    recipient_phone character varying(15),
    recipient_address_id bigint,
    message character varying(500),
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: group_order_members; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.group_order_members (
    id bigint NOT NULL,
    group_order_id bigint NOT NULL,
    customer_id bigint NOT NULL,
    status character varying(20) NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    invite_phone character varying(15),
    amount_contribution numeric(12,2),
    paid boolean DEFAULT false,
    joined_at timestamp(6) without time zone
);


--
-- Name: group_order_members_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.group_order_members ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.group_order_members_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: group_order_participants; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.group_order_participants (
    group_order_id bigint NOT NULL,
    customer_id bigint NOT NULL
);


--
-- Name: group_orders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.group_orders (
    id bigint NOT NULL,
    host_user_id bigint NOT NULL,
    restaurant_id bigint NOT NULL,
    status character varying(20) NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    title character varying(100),
    placed_at timestamp(6) without time zone
);


--
-- Name: group_orders_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.group_orders ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.group_orders_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
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
-- Name: order_delivery_proofs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.order_delivery_proofs (
    id bigint NOT NULL,
    order_id bigint NOT NULL,
    agent_id bigint,
    proof_type character varying(20) DEFAULT 'OTP'::character varying NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    otp_code_hash character varying(128),
    otp_issued_at timestamp without time zone,
    otp_expires_at timestamp without time zone,
    otp_attempts integer DEFAULT 0 NOT NULL,
    verified_at timestamp without time zone,
    photo_storage_key character varying(512),
    photo_uploaded_at timestamp without time zone,
    recipient_name character varying(120),
    capture_latitude double precision,
    capture_longitude double precision,
    notes text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: order_eta_snapshots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.order_eta_snapshots (
    id bigint NOT NULL,
    order_id bigint NOT NULL,
    eta_minutes integer NOT NULL,
    actual_minutes integer,
    created_at timestamp(6) without time zone NOT NULL,
    eta_at timestamp(6) without time zone,
    confidence_low_minutes integer,
    confidence_high_minutes integer,
    traffic_factor double precision,
    surge_multiplier double precision,
    factors_summary character varying(500)
);


--
-- Name: order_eta_snapshots_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.order_eta_snapshots ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.order_eta_snapshots_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: order_invoices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.order_invoices (
    id bigint NOT NULL,
    order_id bigint NOT NULL,
    invoice_number character varying(50) NOT NULL,
    gst_amount numeric(10,2) DEFAULT 0 NOT NULL,
    total numeric(12,2),
    created_at timestamp(6) without time zone NOT NULL,
    subtotal numeric(10,2) DEFAULT 0.0 NOT NULL,
    delivery_fee numeric(10,2) DEFAULT 0.0 NOT NULL,
    tax_amount numeric(10,2) DEFAULT 0.0 NOT NULL,
    cgst_amount numeric(10,2) DEFAULT 0.0 NOT NULL,
    sgst_amount numeric(10,2) DEFAULT 0.0 NOT NULL,
    discount_amount numeric(10,2) DEFAULT 0.0 NOT NULL,
    total_amount numeric(12,2) DEFAULT 0.0 NOT NULL,
    restaurant_gstin character varying(20),
    issued_at timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    pdf_storage_key character varying(512),
    pdf_generated_at timestamp(6) without time zone,
    emailed_at timestamp(6) without time zone,
    email_recipient character varying(255),
    email_attempts integer DEFAULT 0 NOT NULL
);


--
-- Name: order_invoices_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.order_invoices ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.order_invoices_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: order_item_customizations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.order_item_customizations (
    id bigint NOT NULL,
    order_item_id bigint NOT NULL,
    customization_choice_id bigint NOT NULL,
    additional_price double precision
);


--
-- Name: order_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.order_items (
    id bigint NOT NULL,
    order_id bigint NOT NULL,
    menu_item_id bigint NOT NULL,
    item_name character varying(200) NOT NULL,
    unit_price numeric(10,2) NOT NULL,
    quantity integer NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    special_instructions text
);


--
-- Name: order_items_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.order_items ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.order_items_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: order_timeline_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.order_timeline_events (
    id bigint NOT NULL,
    order_id bigint NOT NULL,
    event_type character varying(50) NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    status character varying(30),
    message character varying(500),
    actor_id bigint,
    actor_role character varying(30)
);


--
-- Name: order_timeline_events_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.order_timeline_events ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.order_timeline_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: orders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orders (
    id bigint NOT NULL,
    customer_id bigint NOT NULL,
    restaurant_id bigint NOT NULL,
    status character varying(20) NOT NULL,
    total_amount numeric(12,2) NOT NULL,
    currency character varying(3) DEFAULT 'INR'::character varying NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    order_number character varying(30),
    delivered_at timestamp(6) without time zone,
    estimated_delivery_at timestamp(6) without time zone,
    subtotal numeric(12,2),
    delivery_fee numeric(12,2),
    tax_amount numeric(12,2),
    discount_amount numeric(12,2) DEFAULT 0.0,
    loyalty_points_redeemed integer DEFAULT 0,
    tip_amount numeric(12,2) DEFAULT 0.0,
    delivery_address_id bigint,
    special_instructions text,
    estimated_delivery_time integer,
    scheduled_at timestamp(6) without time zone,
    live_eta_minutes integer,
    live_eta_at timestamp(6) without time zone,
    wallet_amount_used numeric(12,2) DEFAULT 0.0,
    fulfillment_type character varying(20) DEFAULT 'DELIVERY'::character varying,
    device_id character varying(100),
    guest_phone character varying(20),
    gift_message character varying(500),
    recipient_name character varying(100),
    recipient_phone character varying(20),
    cancellation_reason character varying(500),
    cancelled_by character varying(20),
    coupon_id bigint,
    version bigint DEFAULT 0 NOT NULL
);


--
-- Name: orders_archive; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orders_archive (
    id bigint NOT NULL,
    customer_id bigint NOT NULL,
    restaurant_id bigint NOT NULL,
    status character varying(20) NOT NULL,
    total_amount numeric(12,2) NOT NULL,
    currency character varying(3) DEFAULT 'INR'::character varying NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL
)
PARTITION BY RANGE (created_at);


--
-- Name: orders_archive_default; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orders_archive_default (
    id bigint NOT NULL,
    customer_id bigint NOT NULL,
    restaurant_id bigint NOT NULL,
    status character varying(20) NOT NULL,
    total_amount numeric(12,2) NOT NULL,
    currency character varying(3) DEFAULT 'INR'::character varying NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL
);


--
-- Name: orders_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.orders ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.orders_id_seq
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
-- Name: subscription_deliveries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.subscription_deliveries (
    id bigint NOT NULL,
    subscription_plan_id bigint NOT NULL,
    order_id bigint,
    scheduled_date date NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL
);


--
-- Name: subscription_plans; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.subscription_plans (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    restaurant_id bigint NOT NULL,
    title character varying(100),
    items_json text,
    weekday character varying(10) NOT NULL,
    delivery_time time without time zone NOT NULL,
    delivery_address_id bigint NOT NULL,
    payment_method character varying(30) NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    start_date date NOT NULL,
    next_delivery_date date,
    created_at timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP(6) NOT NULL
);


--
-- Name: subscriptions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.subscriptions (
    id bigint NOT NULL,
    customer_id bigint NOT NULL,
    restaurant_id bigint NOT NULL,
    plan character varying(50) NOT NULL,
    status character varying(20) NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL
);


--
-- Name: subscriptions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.subscriptions ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.subscriptions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: orders_archive_default; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders_archive ATTACH PARTITION public.orders_archive_default DEFAULT;


--
-- Name: cart_items cart_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cart_items
    ADD CONSTRAINT cart_items_pkey PRIMARY KEY (id);


--
-- Name: carts carts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.carts
    ADD CONSTRAINT carts_pkey PRIMARY KEY (id);


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
-- Name: dead_letter_events dead_letter_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dead_letter_events
    ADD CONSTRAINT dead_letter_events_pkey PRIMARY KEY (id);


--
-- Name: disputes disputes_order_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.disputes
    ADD CONSTRAINT disputes_order_id_key UNIQUE (order_id);


--
-- Name: disputes disputes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.disputes
    ADD CONSTRAINT disputes_pkey PRIMARY KEY (id);


--
-- Name: gift_cards gift_cards_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.gift_cards
    ADD CONSTRAINT gift_cards_pkey PRIMARY KEY (id);


--
-- Name: group_order_members group_order_members_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.group_order_members
    ADD CONSTRAINT group_order_members_pkey PRIMARY KEY (id);


--
-- Name: group_orders group_orders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.group_orders
    ADD CONSTRAINT group_orders_pkey PRIMARY KEY (id);


--
-- Name: idempotency_records idempotency_records_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_records
    ADD CONSTRAINT idempotency_records_pkey PRIMARY KEY (id);


--
-- Name: order_eta_snapshots order_eta_snapshots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_eta_snapshots
    ADD CONSTRAINT order_eta_snapshots_pkey PRIMARY KEY (id);


--
-- Name: order_invoices order_invoices_order_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_invoices
    ADD CONSTRAINT order_invoices_order_id_key UNIQUE (order_id);


--
-- Name: order_invoices order_invoices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_invoices
    ADD CONSTRAINT order_invoices_pkey PRIMARY KEY (id);


--
-- Name: order_items order_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_items
    ADD CONSTRAINT order_items_pkey PRIMARY KEY (id);


--
-- Name: order_timeline_events order_timeline_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_timeline_events
    ADD CONSTRAINT order_timeline_events_pkey PRIMARY KEY (id);


--
-- Name: orders_archive orders_archive_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders_archive
    ADD CONSTRAINT orders_archive_pkey PRIMARY KEY (id, created_at);


--
-- Name: orders_archive_default orders_archive_default_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders_archive_default
    ADD CONSTRAINT orders_archive_default_pkey PRIMARY KEY (id, created_at);


--
-- Name: orders orders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_pkey PRIMARY KEY (id);


--
-- Name: outbox_events outbox_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outbox_events
    ADD CONSTRAINT outbox_events_pkey PRIMARY KEY (id);


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
-- Name: subscriptions subscriptions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subscriptions
    ADD CONSTRAINT subscriptions_pkey PRIMARY KEY (id);


--
-- Name: coupons uk_coupon_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coupons
    ADD CONSTRAINT uk_coupon_code UNIQUE (code);


--
-- Name: gift_cards uk_gift_card_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.gift_cards
    ADD CONSTRAINT uk_gift_card_code UNIQUE (code);


--
-- Name: group_order_members uk_group_member; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.group_order_members
    ADD CONSTRAINT uk_group_member UNIQUE (group_order_id, customer_id);


--
-- Name: idempotency_records uk_idempotency_scope_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_records
    ADD CONSTRAINT uk_idempotency_scope_key UNIQUE (scope, idempotency_key);


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
-- Name: idx_cart_items_cart; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cart_items_cart ON public.cart_items USING btree (cart_id);


--
-- Name: idx_carts_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_carts_customer ON public.carts USING btree (customer_id);


--
-- Name: idx_coupon_active_valid; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_coupon_active_valid ON public.coupons USING btree (active, valid_from, valid_until);


--
-- Name: idx_coupon_restaurant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_coupon_restaurant ON public.coupons USING btree (restaurant_id);


--
-- Name: idx_coupon_usage_coupon_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_coupon_usage_coupon_customer ON public.coupon_usages USING btree (coupon_id, customer_id);


--
-- Name: idx_delivery_proof_agent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_delivery_proof_agent ON public.order_delivery_proofs USING btree (agent_id, status);


--
-- Name: idx_delivery_proof_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_delivery_proof_status ON public.order_delivery_proofs USING btree (status, created_at);


--
-- Name: idx_dispute_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dispute_created ON public.disputes USING btree (created_at);


--
-- Name: idx_dispute_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dispute_status ON public.disputes USING btree (status);


--
-- Name: idx_dlq_aggregate; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dlq_aggregate ON public.dead_letter_events USING btree (aggregate_type, aggregate_id);


--
-- Name: idx_dlq_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dlq_status_created ON public.dead_letter_events USING btree (status, created_at);


--
-- Name: idx_eta_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_eta_order ON public.order_eta_snapshots USING btree (order_id, created_at);


--
-- Name: idx_gift_orders_sender; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_gift_orders_sender ON public.gift_orders USING btree (sender_user_id);


--
-- Name: idx_gop_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_gop_customer ON public.group_order_participants USING btree (customer_id);


--
-- Name: idx_group_orders_host; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_group_orders_host ON public.group_orders USING btree (host_user_id);


--
-- Name: idx_idempotency_expires; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_idempotency_expires ON public.idempotency_records USING btree (expires_at);


--
-- Name: idx_membership_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_membership_customer ON public.customer_memberships USING btree (customer_id, status);


--
-- Name: idx_order_items_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_order_items_order ON public.order_items USING btree (order_id);


--
-- Name: idx_orders_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_orders_customer ON public.orders USING btree (customer_id, created_at);


--
-- Name: idx_orders_restaurant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_orders_restaurant ON public.orders USING btree (restaurant_id, created_at);


--
-- Name: idx_orders_scheduled_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_orders_scheduled_at ON public.orders USING btree (scheduled_at);


--
-- Name: idx_orders_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_orders_status ON public.orders USING btree (status);


--
-- Name: idx_outbox_aggregate; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_outbox_aggregate ON public.outbox_events USING btree (aggregate_type, aggregate_id);


--
-- Name: idx_outbox_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_outbox_status_created ON public.outbox_events USING btree (status, created_at);


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
-- Name: idx_sub_del_plan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sub_del_plan ON public.subscription_deliveries USING btree (subscription_plan_id);


--
-- Name: idx_sub_del_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sub_del_status ON public.subscription_deliveries USING btree (status);


--
-- Name: idx_sub_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sub_status ON public.subscription_plans USING btree (status);


--
-- Name: idx_sub_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sub_user ON public.subscription_plans USING btree (user_id);


--
-- Name: idx_subscriptions_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_subscriptions_customer ON public.subscriptions USING btree (customer_id, status);


--
-- Name: idx_timeline_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_timeline_order ON public.order_timeline_events USING btree (order_id, created_at);


--
-- Name: orders_archive_default_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.orders_archive_pkey ATTACH PARTITION public.orders_archive_default_pkey;


--
-- Name: cart_items cart_items_cart_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cart_items
    ADD CONSTRAINT cart_items_cart_id_fkey FOREIGN KEY (cart_id) REFERENCES public.carts(id) ON DELETE CASCADE;


--
-- Name: coupon_usages coupon_usages_coupon_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coupon_usages
    ADD CONSTRAINT coupon_usages_coupon_id_fkey FOREIGN KEY (coupon_id) REFERENCES public.coupons(id) ON DELETE CASCADE;


--
-- Name: coupon_usages coupon_usages_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coupon_usages
    ADD CONSTRAINT coupon_usages_order_id_fkey FOREIGN KEY (order_id) REFERENCES public.orders(id) ON DELETE SET NULL;


--
-- Name: disputes disputes_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.disputes
    ADD CONSTRAINT disputes_order_id_fkey FOREIGN KEY (order_id) REFERENCES public.orders(id) ON DELETE CASCADE;


--
-- Name: saga_steps fk_saga_steps_instance; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saga_steps
    ADD CONSTRAINT fk_saga_steps_instance FOREIGN KEY (saga_instance_id) REFERENCES public.saga_instances(id) ON DELETE CASCADE;


--
-- Name: group_order_members group_order_members_group_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.group_order_members
    ADD CONSTRAINT group_order_members_group_order_id_fkey FOREIGN KEY (group_order_id) REFERENCES public.group_orders(id) ON DELETE CASCADE;


--
-- Name: order_eta_snapshots order_eta_snapshots_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_eta_snapshots
    ADD CONSTRAINT order_eta_snapshots_order_id_fkey FOREIGN KEY (order_id) REFERENCES public.orders(id);


--
-- Name: order_invoices order_invoices_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_invoices
    ADD CONSTRAINT order_invoices_order_id_fkey FOREIGN KEY (order_id) REFERENCES public.orders(id);


--
-- Name: order_items order_items_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_items
    ADD CONSTRAINT order_items_order_id_fkey FOREIGN KEY (order_id) REFERENCES public.orders(id) ON DELETE CASCADE;


--
-- Name: order_timeline_events order_timeline_events_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_timeline_events
    ADD CONSTRAINT order_timeline_events_order_id_fkey FOREIGN KEY (order_id) REFERENCES public.orders(id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--


