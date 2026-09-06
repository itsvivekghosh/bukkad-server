-- identity service schema - consolidated baseline (fresh start).
-- Derived from squashed platform baseline + service migrations.
--
-- PostgreSQL database dump
--


-- Dumped from database version 16.15
-- Dumped by pg_dump version 16.15




--
-- Name: addresses; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.addresses (
    id bigint NOT NULL,
    customer_id bigint NOT NULL,
    label character varying(50),
    line1 character varying(255) NOT NULL,
    city character varying(100) NOT NULL,
    state character varying(100),
    zip_code character varying(10),
    is_default boolean DEFAULT false NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    line2 character varying(255),
    landmark character varying(200),
    type character varying(20),
    latitude double precision DEFAULT 0.0 NOT NULL,
    longitude double precision DEFAULT 0.0 NOT NULL
);


--
-- Name: addresses_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.addresses ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.addresses_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: admins; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.admins (
    id bigint NOT NULL,
    email character varying(100),
    full_name character varying(100),
    phone_number character varying(15),
    created_at timestamp(6) without time zone DEFAULT now(),
    password character varying(255),
    profile_image_url character varying(500),
    totp_secret character varying(255)
);


--
-- Name: affiliate_codes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.affiliate_codes (
    id bigint NOT NULL,
    code character varying(40) NOT NULL,
    created_at timestamp(6) without time zone DEFAULT now(),
    name character varying(120) NOT NULL,
    channel character varying(40),
    reward_amount double precision DEFAULT 0.0 NOT NULL,
    is_active boolean DEFAULT true
);


--
-- Name: affiliate_codes_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.affiliate_codes ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.affiliate_codes_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: affiliate_referrals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.affiliate_referrals (
    id bigint NOT NULL,
    status character varying(20) NOT NULL,
    created_at timestamp(6) without time zone DEFAULT now(),
    affiliate_code_id bigint NOT NULL,
    customer_id bigint NOT NULL,
    reward_amount double precision DEFAULT 0.0 NOT NULL
);


--
-- Name: affiliate_referrals_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.affiliate_referrals ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.affiliate_referrals_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: api_key_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.api_key_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: api_keys; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.api_keys (
    id bigint DEFAULT nextval('public.api_key_seq'::regclass) NOT NULL,
    name character varying(100) NOT NULL,
    key_prefix character varying(20) NOT NULL,
    key_hash character(64) NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    partner_id bigint,
    scopes character varying(500),
    expires_at timestamp(6) without time zone,
    last_used_at timestamp(6) without time zone,
    created_at timestamp(6) without time zone NOT NULL,
    revoked_at timestamp(6) without time zone
);


--
-- Name: consent_records; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.consent_records (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    purpose character varying(50) NOT NULL,
    granted boolean NOT NULL,
    created_at timestamp(6) without time zone DEFAULT now()
);


--
-- Name: consent_records_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.consent_records ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.consent_records_id_seq
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
    status character varying(20) NOT NULL,
    created_at timestamp(6) without time zone DEFAULT now(),
    starts_at timestamp(6) without time zone NOT NULL,
    ends_at timestamp(6) without time zone NOT NULL
);


--
-- Name: customer_memberships_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.customer_memberships ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.customer_memberships_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: customer_notification_preferences; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customer_notification_preferences (
    id bigint NOT NULL,
    customer_id bigint NOT NULL,
    channel character varying(20) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp(6) without time zone DEFAULT now()
);


--
-- Name: customer_notification_preferences_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.customer_notification_preferences ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.customer_notification_preferences_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: customers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customers (
    id bigint NOT NULL,
    email character varying(100) NOT NULL,
    phone_number character varying(15),
    full_name character varying(100) NOT NULL,
    password_hash character varying(255) NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    email_verified boolean DEFAULT false NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    referral_code character varying(32),
    referred_by_id bigint,
    wallet_balance double precision DEFAULT 0.0 NOT NULL,
    profile_image_url character varying(500),
    totp_secret character varying(255),
    loyalty_points integer DEFAULT 0 NOT NULL
);


--
-- Name: customers_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.customers ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.customers_id_seq
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
-- Name: delivery_agents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.delivery_agents (
    id bigint NOT NULL,
    email character varying(100),
    full_name character varying(100),
    phone_number character varying(15),
    available boolean DEFAULT false NOT NULL,
    verified boolean DEFAULT false NOT NULL,
    current_latitude double precision,
    current_longitude double precision,
    average_rating double precision DEFAULT 0.0 NOT NULL,
    total_deliveries bigint DEFAULT 0 NOT NULL,
    created_at timestamp(6) without time zone DEFAULT now(),
    vehicle_type character varying(50),
    vehicle_number character varying(50),
    license_number character varying(50)
);


--
-- Name: device_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.device_tokens (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    token character varying(512) NOT NULL,
    device_type character varying(20),
    created_at timestamp(6) without time zone DEFAULT now(),
    platform character varying(20) DEFAULT 'WEB'::character varying NOT NULL,
    active boolean DEFAULT true NOT NULL,
    updated_at timestamp(6) without time zone
);


--
-- Name: device_tokens_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.device_tokens ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.device_tokens_id_seq
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
    created_at timestamp(6) without time zone DEFAULT now()
);


--
-- Name: favorite_restaurants_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.favorite_restaurants ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.favorite_restaurants_id_seq
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
-- Name: membership_plans; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.membership_plans (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    created_at timestamp(6) without time zone DEFAULT now(),
    description text,
    price_per_month double precision NOT NULL,
    free_delivery boolean DEFAULT true NOT NULL,
    discount_percent double precision DEFAULT 0.0 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    tier_level integer DEFAULT 0,
    max_discount_percent double precision DEFAULT 0.0,
    referral_bonus_percent double precision DEFAULT 0.0,
    referral_max_per_month integer DEFAULT 0
);


--
-- Name: membership_plans_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.membership_plans ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.membership_plans_id_seq
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
-- Name: restaurant_owners; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.restaurant_owners (
    id bigint NOT NULL,
    email character varying(100),
    full_name character varying(100),
    phone_number character varying(15),
    business_license character varying(100),
    verified boolean DEFAULT false NOT NULL,
    created_at timestamp(6) without time zone DEFAULT now(),
    password character varying(255),
    profile_image_url character varying(500),
    totp_secret character varying(255)
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
    domain character varying(120) NOT NULL,
    created_at timestamp(6) without time zone DEFAULT now(),
    brand_name character varying(120),
    logo_url character varying(500),
    theme_color character varying(30),
    currency character varying(10) DEFAULT 'INR'::character varying NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    updated_at timestamp(6) without time zone
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
-- Name: user_referral_codes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_referral_codes (
    user_id bigint NOT NULL,
    code character varying(20) NOT NULL
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id bigint NOT NULL,
    role character varying(20) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    email_verified boolean DEFAULT false NOT NULL,
    phone_verified boolean DEFAULT false NOT NULL,
    phone_verified_at timestamp(6) without time zone,
    profile_completed boolean DEFAULT false NOT NULL,
    totp_enabled boolean DEFAULT false NOT NULL,
    created_at timestamp(6) without time zone DEFAULT now(),
    updated_at timestamp(6) without time zone NOT NULL
);


--
-- Name: users_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.users ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.users_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: addresses addresses_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.addresses
    ADD CONSTRAINT addresses_pkey PRIMARY KEY (id);


--
-- Name: admins admins_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.admins
    ADD CONSTRAINT admins_pkey PRIMARY KEY (id);


--
-- Name: affiliate_codes affiliate_codes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.affiliate_codes
    ADD CONSTRAINT affiliate_codes_pkey PRIMARY KEY (id);


--
-- Name: affiliate_referrals affiliate_referrals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.affiliate_referrals
    ADD CONSTRAINT affiliate_referrals_pkey PRIMARY KEY (id);


--
-- Name: consent_records consent_records_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consent_records
    ADD CONSTRAINT consent_records_pkey PRIMARY KEY (id);


--
-- Name: customer_memberships customer_memberships_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_memberships
    ADD CONSTRAINT customer_memberships_pkey PRIMARY KEY (id);


--
-- Name: customer_notification_preferences customer_notification_preferences_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_notification_preferences
    ADD CONSTRAINT customer_notification_preferences_pkey PRIMARY KEY (id);


--
-- Name: customers customers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customers
    ADD CONSTRAINT customers_pkey PRIMARY KEY (id);


--
-- Name: dead_letter_events dead_letter_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dead_letter_events
    ADD CONSTRAINT dead_letter_events_pkey PRIMARY KEY (id);


--
-- Name: delivery_agents delivery_agents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.delivery_agents
    ADD CONSTRAINT delivery_agents_pkey PRIMARY KEY (id);


--
-- Name: device_tokens device_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.device_tokens
    ADD CONSTRAINT device_tokens_pkey PRIMARY KEY (id);


--
-- Name: favorite_restaurants favorite_restaurants_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.favorite_restaurants
    ADD CONSTRAINT favorite_restaurants_pkey PRIMARY KEY (id);


--
-- Name: idempotency_records idempotency_records_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_records
    ADD CONSTRAINT idempotency_records_pkey PRIMARY KEY (id);


--
-- Name: membership_plans membership_plans_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.membership_plans
    ADD CONSTRAINT membership_plans_pkey PRIMARY KEY (id);


--
-- Name: outbox_events outbox_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outbox_events
    ADD CONSTRAINT outbox_events_pkey PRIMARY KEY (id);


--
-- Name: api_keys pk_api_keys; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_keys
    ADD CONSTRAINT pk_api_keys PRIMARY KEY (id);


--
-- Name: restaurant_owners restaurant_owners_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.restaurant_owners
    ADD CONSTRAINT restaurant_owners_pkey PRIMARY KEY (id);


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
-- Name: affiliate_codes uk_affiliate_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.affiliate_codes
    ADD CONSTRAINT uk_affiliate_code UNIQUE (code);


--
-- Name: consent_records uk_consent_user_purpose; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consent_records
    ADD CONSTRAINT uk_consent_user_purpose UNIQUE (user_id, purpose);


--
-- Name: customers uk_customer_email; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customers
    ADD CONSTRAINT uk_customer_email UNIQUE (email);


--
-- Name: favorite_restaurants uk_favorite_customer_restaurant; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.favorite_restaurants
    ADD CONSTRAINT uk_favorite_customer_restaurant UNIQUE (customer_id, restaurant_id);


--
-- Name: idempotency_records uk_idempotency_scope_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_records
    ADD CONSTRAINT uk_idempotency_scope_key UNIQUE (scope, idempotency_key);


--
-- Name: customer_notification_preferences uk_notif_pref_customer_channel; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_notification_preferences
    ADD CONSTRAINT uk_notif_pref_customer_channel UNIQUE (customer_id, channel);


--
-- Name: tenants uk_tenants_domain; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenants
    ADD CONSTRAINT uk_tenants_domain UNIQUE (domain);


--
-- Name: user_referral_codes uk_user_referral_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_referral_codes
    ADD CONSTRAINT uk_user_referral_code UNIQUE (code);


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
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: idx_address_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_address_customer ON public.addresses USING btree (customer_id);


--
-- Name: idx_admin_phone; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_admin_phone ON public.admins USING btree (phone_number);


--
-- Name: idx_affiliate_referral_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_affiliate_referral_code ON public.affiliate_referrals USING btree (affiliate_code_id);


--
-- Name: idx_affiliate_referral_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_affiliate_referral_status ON public.affiliate_referrals USING btree (status);


--
-- Name: idx_agent_available_verified; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_available_verified ON public.delivery_agents USING btree (available, verified);


--
-- Name: idx_customer_phone; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_customer_phone ON public.customers USING btree (phone_number);


--
-- Name: idx_customer_referred_by_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_customer_referred_by_id ON public.customers USING btree (referred_by_id);


--
-- Name: idx_device_tokens_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_device_tokens_user ON public.device_tokens USING btree (user_id);


--
-- Name: idx_dlq_aggregate; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dlq_aggregate ON public.dead_letter_events USING btree (aggregate_type, aggregate_id);


--
-- Name: idx_dlq_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dlq_status_created ON public.dead_letter_events USING btree (status, created_at);


--
-- Name: idx_favorites_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_favorites_customer ON public.favorite_restaurants USING btree (customer_id);


--
-- Name: idx_idempotency_expires; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_idempotency_expires ON public.idempotency_records USING btree (expires_at);


--
-- Name: idx_membership_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_membership_customer ON public.customer_memberships USING btree (customer_id, status);


--
-- Name: idx_outbox_aggregate; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_outbox_aggregate ON public.outbox_events USING btree (aggregate_type, aggregate_id);


--
-- Name: idx_outbox_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_outbox_status_created ON public.outbox_events USING btree (status, created_at);


--
-- Name: idx_owner_verified; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_owner_verified ON public.restaurant_owners USING btree (verified);


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
-- Name: idx_user_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_active ON public.users USING btree (active);


--
-- Name: idx_user_role; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_role ON public.users USING btree (role);


--
-- Name: uk_customer_referral_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_customer_referral_code ON public.customers USING btree (referral_code) WHERE (referral_code IS NOT NULL);


--
-- Name: addresses addresses_customer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.addresses
    ADD CONSTRAINT addresses_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customers(id) ON DELETE CASCADE;


--
-- Name: admins admins_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.admins
    ADD CONSTRAINT admins_id_fkey FOREIGN KEY (id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: consent_records consent_records_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consent_records
    ADD CONSTRAINT consent_records_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: customer_memberships customer_memberships_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_memberships
    ADD CONSTRAINT customer_memberships_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.membership_plans(id);


--
-- Name: customer_notification_preferences customer_notification_preferences_customer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_notification_preferences
    ADD CONSTRAINT customer_notification_preferences_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: customers customers_referred_by_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customers
    ADD CONSTRAINT customers_referred_by_id_fkey FOREIGN KEY (referred_by_id) REFERENCES public.customers(id);


--
-- Name: delivery_agents delivery_agents_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.delivery_agents
    ADD CONSTRAINT delivery_agents_id_fkey FOREIGN KEY (id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: device_tokens device_tokens_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.device_tokens
    ADD CONSTRAINT device_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: favorite_restaurants favorite_restaurants_customer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.favorite_restaurants
    ADD CONSTRAINT favorite_restaurants_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: affiliate_referrals fk_affiliate_referral_code; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.affiliate_referrals
    ADD CONSTRAINT fk_affiliate_referral_code FOREIGN KEY (affiliate_code_id) REFERENCES public.affiliate_codes(id);


--
-- Name: affiliate_referrals fk_affiliate_referral_customer; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.affiliate_referrals
    ADD CONSTRAINT fk_affiliate_referral_customer FOREIGN KEY (customer_id) REFERENCES public.customers(id);


--
-- Name: customer_memberships fk_membership_customer; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_memberships
    ADD CONSTRAINT fk_membership_customer FOREIGN KEY (customer_id) REFERENCES public.customers(id) ON DELETE CASCADE;


--
-- Name: saga_steps fk_saga_steps_instance; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saga_steps
    ADD CONSTRAINT fk_saga_steps_instance FOREIGN KEY (saga_instance_id) REFERENCES public.saga_instances(id) ON DELETE CASCADE;


--
-- Name: restaurant_owners restaurant_owners_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.restaurant_owners
    ADD CONSTRAINT restaurant_owners_id_fkey FOREIGN KEY (id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: user_referral_codes user_referral_codes_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_referral_codes
    ADD CONSTRAINT user_referral_codes_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--


