-- admin-analytics service schema - consolidated baseline (fresh start).
-- Derived from squashed platform baseline + service migrations.
--
-- PostgreSQL database dump
--


-- Dumped from database version 16.15
-- Dumped by pg_dump version 16.15




--
-- Name: analytics_export_tasks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.analytics_export_tasks (
    id bigint NOT NULL,
    export_type character varying(50) NOT NULL,
    status character varying(20) NOT NULL,
    file_url character varying(500),
    filters text,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL
);


--
-- Name: analytics_export_tasks_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.analytics_export_tasks ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.analytics_export_tasks_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: api_keys; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.api_keys (
    id bigint NOT NULL,
    key_hash character varying(64) NOT NULL,
    name character varying(100) NOT NULL,
    status character varying(20) NOT NULL,
    expires_at timestamp(6) without time zone NOT NULL,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: api_keys_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.api_keys ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.api_keys_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: audit_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_events (
    id bigint NOT NULL,
    actor_type character varying(30),
    actor_id bigint,
    action character varying(100) NOT NULL,
    entity_type character varying(50),
    entity_id bigint,
    details text,
    created_at timestamp(6) without time zone NOT NULL,
    actor_role character varying(30),
    old_state text,
    new_state text,
    ip_address character varying(45),
    trace_id character varying(64),
    request_id character varying(64),
    resource_type character varying(80),
    resource_id character varying(100)
);


--
-- Name: audit_events_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.audit_events ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.audit_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: campaign_usages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.campaign_usages (
    id bigint NOT NULL,
    campaign_id bigint NOT NULL,
    customer_id bigint NOT NULL,
    order_id bigint,
    used_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: churn_scores; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.churn_scores (
    id bigint NOT NULL,
    customer_id bigint NOT NULL,
    score double precision NOT NULL,
    model_version character varying(20) NOT NULL,
    features_json text,
    computed_at timestamp(6) without time zone NOT NULL,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: churn_scores_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.churn_scores ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.churn_scores_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: data_export_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.data_export_requests (
    id bigint NOT NULL,
    customer_id bigint NOT NULL,
    format character varying(10) NOT NULL,
    status character varying(20) NOT NULL,
    file_url character varying(500),
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL
);


--
-- Name: data_export_requests_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.data_export_requests ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.data_export_requests_id_seq
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
-- Name: experiment_exposures; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.experiment_exposures (
    id bigint NOT NULL,
    experiment_key character varying(80) NOT NULL,
    user_id bigint NOT NULL,
    variant character varying(80) NOT NULL,
    bucket integer NOT NULL,
    exposed_at timestamp(6) without time zone NOT NULL
);


--
-- Name: experiment_exposures_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.experiment_exposures ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.experiment_exposures_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: feature_flags; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.feature_flags (
    id bigint NOT NULL,
    flag_name character varying(100) NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: feature_flags_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.feature_flags ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.feature_flags_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: fraud_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fraud_events (
    id bigint NOT NULL,
    customer_id bigint NOT NULL,
    rule character varying(100) NOT NULL,
    severity character varying(20) NOT NULL,
    status character varying(20) NOT NULL,
    details text,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: fraud_events_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.fraud_events ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.fraud_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: fraud_review_queue; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fraud_review_queue (
    id bigint NOT NULL,
    customer_id bigint NOT NULL,
    rule character varying(100) NOT NULL,
    severity character varying(20) NOT NULL,
    status character varying(20) NOT NULL,
    assigned_to character varying(100),
    notes text,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL
);


--
-- Name: fraud_review_queue_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.fraud_review_queue ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.fraud_review_queue_id_seq
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
    title character varying(150) NOT NULL,
    subtitle character varying(255),
    image_url character varying(500),
    action_type character varying(30) DEFAULT 'NONE'::character varying NOT NULL,
    action_target character varying(255),
    display_order integer DEFAULT 0 NOT NULL,
    is_active boolean DEFAULT false NOT NULL,
    starts_at timestamp without time zone,
    ends_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: promotion_campaigns; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.promotion_campaigns (
    id bigint NOT NULL,
    name character varying(120) NOT NULL,
    campaign_type character varying(30) NOT NULL,
    description text,
    discount_percent double precision,
    min_order_amount double precision,
    is_active boolean DEFAULT false NOT NULL,
    starts_at timestamp without time zone,
    ends_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    restaurant_id bigint,
    max_discount_amount double precision,
    flat_discount_amount double precision,
    free_delivery boolean DEFAULT false NOT NULL,
    priority integer DEFAULT 0 NOT NULL,
    usage_limit integer,
    per_user_limit integer,
    buy_quantity integer,
    get_quantity integer,
    get_discount_percent double precision,
    target_segment character varying(20),
    applicable_menu_item_id bigint
);


--
-- Name: restaurant_order_stats; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.restaurant_order_stats (
    restaurant_id bigint NOT NULL,
    order_count bigint DEFAULT 0 NOT NULL,
    revenue numeric(14,2) DEFAULT 0 NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL
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
-- Name: analytics_export_tasks analytics_export_tasks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analytics_export_tasks
    ADD CONSTRAINT analytics_export_tasks_pkey PRIMARY KEY (id);


--
-- Name: api_keys api_keys_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_keys
    ADD CONSTRAINT api_keys_pkey PRIMARY KEY (id);


--
-- Name: audit_events audit_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_events
    ADD CONSTRAINT audit_events_pkey PRIMARY KEY (id);


--
-- Name: churn_scores churn_scores_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.churn_scores
    ADD CONSTRAINT churn_scores_pkey PRIMARY KEY (id);


--
-- Name: data_export_requests data_export_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.data_export_requests
    ADD CONSTRAINT data_export_requests_pkey PRIMARY KEY (id);


--
-- Name: dead_letter_events dead_letter_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dead_letter_events
    ADD CONSTRAINT dead_letter_events_pkey PRIMARY KEY (id);


--
-- Name: experiment_exposures experiment_exposures_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.experiment_exposures
    ADD CONSTRAINT experiment_exposures_pkey PRIMARY KEY (id);


--
-- Name: feature_flags feature_flags_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feature_flags
    ADD CONSTRAINT feature_flags_pkey PRIMARY KEY (id);


--
-- Name: fraud_events fraud_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fraud_events
    ADD CONSTRAINT fraud_events_pkey PRIMARY KEY (id);


--
-- Name: fraud_review_queue fraud_review_queue_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fraud_review_queue
    ADD CONSTRAINT fraud_review_queue_pkey PRIMARY KEY (id);


--
-- Name: idempotency_records idempotency_records_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_records
    ADD CONSTRAINT idempotency_records_pkey PRIMARY KEY (id);


--
-- Name: outbox_events outbox_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outbox_events
    ADD CONSTRAINT outbox_events_pkey PRIMARY KEY (id);


--
-- Name: restaurant_order_stats restaurant_order_stats_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.restaurant_order_stats
    ADD CONSTRAINT restaurant_order_stats_pkey PRIMARY KEY (restaurant_id);


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
-- Name: api_keys uk_api_key_hash; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_keys
    ADD CONSTRAINT uk_api_key_hash UNIQUE (key_hash);


--
-- Name: experiment_exposures uk_experiment_user; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.experiment_exposures
    ADD CONSTRAINT uk_experiment_user UNIQUE (experiment_key, user_id);


--
-- Name: feature_flags uk_feature_flag_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feature_flags
    ADD CONSTRAINT uk_feature_flag_name UNIQUE (flag_name);


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
-- Name: idx_audit_actor; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_actor ON public.audit_events USING btree (actor_type, actor_id, created_at);


--
-- Name: idx_audit_entity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_entity ON public.audit_events USING btree (entity_type, entity_id);


--
-- Name: idx_churn_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_churn_customer ON public.churn_scores USING btree (customer_id, computed_at);


--
-- Name: idx_dlq_aggregate; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dlq_aggregate ON public.dead_letter_events USING btree (aggregate_type, aggregate_id);


--
-- Name: idx_dlq_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dlq_status_created ON public.dead_letter_events USING btree (status, created_at);


--
-- Name: idx_experiment_exposed_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_experiment_exposed_at ON public.experiment_exposures USING btree (exposed_at);


--
-- Name: idx_experiment_variant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_experiment_variant ON public.experiment_exposures USING btree (experiment_key, variant);


--
-- Name: idx_fraud_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fraud_customer ON public.fraud_events USING btree (customer_id, created_at);


--
-- Name: idx_fraud_review_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fraud_review_customer ON public.fraud_review_queue USING btree (customer_id);


--
-- Name: idx_fraud_review_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fraud_review_status ON public.fraud_review_queue USING btree (status);


--
-- Name: idx_fraud_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fraud_status ON public.fraud_events USING btree (status);


--
-- Name: idx_idempotency_expires; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_idempotency_expires ON public.idempotency_records USING btree (expires_at);


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
-- Name: saga_steps fk_saga_steps_instance; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saga_steps
    ADD CONSTRAINT fk_saga_steps_instance FOREIGN KEY (saga_instance_id) REFERENCES public.saga_instances(id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--


