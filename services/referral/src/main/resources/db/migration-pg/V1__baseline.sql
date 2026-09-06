-- referral service schema - consolidated baseline (fresh start).
-- Derived from squashed platform baseline + service migrations.
--
-- PostgreSQL database dump
--


-- Dumped from database version 16.15
-- Dumped by pg_dump version 16.15




--
-- Name: affiliate_codes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.affiliate_codes (
    id bigint NOT NULL,
    code character varying(40) NOT NULL,
    name character varying(120) NOT NULL,
    channel character varying(40),
    reward_amount double precision DEFAULT 0.0 NOT NULL,
    is_active boolean DEFAULT false NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: affiliate_codes_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.affiliate_codes_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: affiliate_codes_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.affiliate_codes_id_seq OWNED BY public.affiliate_codes.id;


--
-- Name: affiliate_referrals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.affiliate_referrals (
    id bigint NOT NULL,
    affiliate_code_id bigint NOT NULL,
    customer_id bigint NOT NULL,
    customer_email character varying(200),
    reward_amount double precision DEFAULT 0.0 NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: affiliate_referrals_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.affiliate_referrals_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: affiliate_referrals_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.affiliate_referrals_id_seq OWNED BY public.affiliate_referrals.id;


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
-- Name: user_referral_codes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_referral_codes (
    id bigint NOT NULL,
    customer_id bigint NOT NULL,
    referral_code character varying(40) NOT NULL,
    referred_by bigint,
    referrals_count integer DEFAULT 0 NOT NULL,
    referral_bonus_earned double precision DEFAULT 0.0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone
);


--
-- Name: user_referral_codes_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.user_referral_codes_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: user_referral_codes_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.user_referral_codes_id_seq OWNED BY public.user_referral_codes.id;


--
-- Name: affiliate_codes id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.affiliate_codes ALTER COLUMN id SET DEFAULT nextval('public.affiliate_codes_id_seq'::regclass);


--
-- Name: affiliate_referrals id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.affiliate_referrals ALTER COLUMN id SET DEFAULT nextval('public.affiliate_referrals_id_seq'::regclass);


--
-- Name: user_referral_codes id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_referral_codes ALTER COLUMN id SET DEFAULT nextval('public.user_referral_codes_id_seq'::regclass);


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
-- Name: dead_letter_events dead_letter_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dead_letter_events
    ADD CONSTRAINT dead_letter_events_pkey PRIMARY KEY (id);


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
-- Name: affiliate_codes uk_affiliate_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.affiliate_codes
    ADD CONSTRAINT uk_affiliate_code UNIQUE (code);


--
-- Name: idempotency_records uk_idempotency_scope_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_records
    ADD CONSTRAINT uk_idempotency_scope_key UNIQUE (scope, idempotency_key);


--
-- Name: user_referral_codes uk_referral_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_referral_codes
    ADD CONSTRAINT uk_referral_code UNIQUE (referral_code);


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
-- Name: user_referral_codes user_referral_codes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_referral_codes
    ADD CONSTRAINT user_referral_codes_pkey PRIMARY KEY (id);


--
-- Name: idx_affiliate_codes_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_affiliate_codes_active ON public.affiliate_codes USING btree (is_active);


--
-- Name: idx_affiliate_referral_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_affiliate_referral_code ON public.affiliate_referrals USING btree (affiliate_code_id);


--
-- Name: idx_affiliate_referral_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_affiliate_referral_customer ON public.affiliate_referrals USING btree (customer_id);


--
-- Name: idx_affiliate_referral_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_affiliate_referral_status ON public.affiliate_referrals USING btree (status);


--
-- Name: idx_dlq_aggregate; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dlq_aggregate ON public.dead_letter_events USING btree (aggregate_type, aggregate_id);


--
-- Name: idx_dlq_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dlq_status_created ON public.dead_letter_events USING btree (status, created_at);


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
-- Name: idx_referral_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_referral_customer ON public.user_referral_codes USING btree (customer_id);


--
-- Name: idx_referral_referred_by; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_referral_referred_by ON public.user_referral_codes USING btree (referred_by);


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
-- Name: affiliate_referrals fk_affiliate_referral_code; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.affiliate_referrals
    ADD CONSTRAINT fk_affiliate_referral_code FOREIGN KEY (affiliate_code_id) REFERENCES public.affiliate_codes(id) ON DELETE CASCADE;


--
-- Name: saga_steps fk_saga_steps_instance; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saga_steps
    ADD CONSTRAINT fk_saga_steps_instance FOREIGN KEY (saga_instance_id) REFERENCES public.saga_instances(id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--


