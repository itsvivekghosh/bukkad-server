-- delivery service schema - consolidated baseline (fresh start).
-- Derived from squashed platform baseline + service migrations.
--
-- PostgreSQL database dump
--


-- Dumped from database version 16.15
-- Dumped by pg_dump version 16.15




--
-- Name: agent_cod_wallets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_cod_wallets (
    id bigint NOT NULL,
    agent_id bigint NOT NULL,
    balance numeric(12,2) DEFAULT 0 NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL
);


--
-- Name: agent_cod_wallets_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.agent_cod_wallets ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.agent_cod_wallets_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: agent_shifts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_shifts (
    id bigint NOT NULL,
    agent_id bigint NOT NULL,
    start_time timestamp(6) without time zone NOT NULL,
    end_time timestamp(6) without time zone,
    status character varying(20) NOT NULL,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: agent_shifts_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.agent_shifts ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.agent_shifts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: city_configs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.city_configs (
    id bigint NOT NULL,
    city_name character varying(100) NOT NULL,
    currency character varying(3) DEFAULT 'INR'::character varying NOT NULL,
    timezone character varying(50) DEFAULT 'Asia/Kolkata'::character varying NOT NULL,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: city_configs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.city_configs ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.city_configs_id_seq
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
    name character varying(100) NOT NULL,
    phone character varying(20),
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL
);


--
-- Name: delivery_agents_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.delivery_agents ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.delivery_agents_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: delivery_assignments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.delivery_assignments (
    id bigint NOT NULL,
    order_id bigint NOT NULL,
    agent_id bigint NOT NULL,
    status character varying(20) NOT NULL,
    assigned_at timestamp(6) without time zone NOT NULL,
    picked_up_at timestamp(6) without time zone,
    delivered_at timestamp(6) without time zone,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: delivery_assignments_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.delivery_assignments ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.delivery_assignments_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: delivery_zones; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.delivery_zones (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: delivery_zones_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.delivery_zones ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.delivery_zones_id_seq
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
    photo_url character varying(500),
    notes character varying(500),
    signature text,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: order_delivery_proofs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.order_delivery_proofs ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.order_delivery_proofs_id_seq
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
-- Name: rider_delivery_batch_orders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rider_delivery_batch_orders (
    id bigint NOT NULL,
    batch_id bigint NOT NULL,
    order_id bigint NOT NULL,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: rider_delivery_batch_orders_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.rider_delivery_batch_orders ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.rider_delivery_batch_orders_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: rider_delivery_batches; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rider_delivery_batches (
    id bigint NOT NULL,
    agent_id bigint NOT NULL,
    status character varying(20) NOT NULL,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: rider_delivery_batches_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.rider_delivery_batches ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.rider_delivery_batches_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: rider_earnings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rider_earnings (
    id bigint NOT NULL,
    agent_id bigint NOT NULL,
    order_id bigint NOT NULL,
    amount numeric(10,2) NOT NULL,
    status character varying(20) NOT NULL,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: rider_earnings_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.rider_earnings ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.rider_earnings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: rider_location_updates; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rider_location_updates (
    id bigint NOT NULL,
    agent_id bigint NOT NULL,
    latitude double precision NOT NULL,
    longitude double precision NOT NULL,
    recorded_at timestamp(6) without time zone NOT NULL,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: rider_location_updates_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.rider_location_updates ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.rider_location_updates_id_seq
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
-- Name: zone_surge_rules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.zone_surge_rules (
    id bigint NOT NULL,
    zone_id bigint NOT NULL,
    day_of_week integer,
    start_time time without time zone NOT NULL,
    end_time time without time zone NOT NULL,
    multiplier numeric(5,2) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp(6) without time zone NOT NULL
);


--
-- Name: zone_surge_rules_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.zone_surge_rules ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.zone_surge_rules_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: agent_cod_wallets agent_cod_wallets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_cod_wallets
    ADD CONSTRAINT agent_cod_wallets_pkey PRIMARY KEY (id);


--
-- Name: agent_shifts agent_shifts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_shifts
    ADD CONSTRAINT agent_shifts_pkey PRIMARY KEY (id);


--
-- Name: city_configs city_configs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.city_configs
    ADD CONSTRAINT city_configs_pkey PRIMARY KEY (id);


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
-- Name: delivery_assignments delivery_assignments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.delivery_assignments
    ADD CONSTRAINT delivery_assignments_pkey PRIMARY KEY (id);


--
-- Name: delivery_zones delivery_zones_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.delivery_zones
    ADD CONSTRAINT delivery_zones_pkey PRIMARY KEY (id);


--
-- Name: idempotency_records idempotency_records_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_records
    ADD CONSTRAINT idempotency_records_pkey PRIMARY KEY (id);


--
-- Name: order_delivery_proofs order_delivery_proofs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_delivery_proofs
    ADD CONSTRAINT order_delivery_proofs_pkey PRIMARY KEY (id);


--
-- Name: outbox_events outbox_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outbox_events
    ADD CONSTRAINT outbox_events_pkey PRIMARY KEY (id);


--
-- Name: rider_delivery_batch_orders rider_delivery_batch_orders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rider_delivery_batch_orders
    ADD CONSTRAINT rider_delivery_batch_orders_pkey PRIMARY KEY (id);


--
-- Name: rider_delivery_batches rider_delivery_batches_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rider_delivery_batches
    ADD CONSTRAINT rider_delivery_batches_pkey PRIMARY KEY (id);


--
-- Name: rider_earnings rider_earnings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rider_earnings
    ADD CONSTRAINT rider_earnings_pkey PRIMARY KEY (id);


--
-- Name: rider_location_updates rider_location_updates_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rider_location_updates
    ADD CONSTRAINT rider_location_updates_pkey PRIMARY KEY (id);


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
-- Name: agent_cod_wallets uk_agent_cod_wallet; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_cod_wallets
    ADD CONSTRAINT uk_agent_cod_wallet UNIQUE (agent_id);


--
-- Name: rider_delivery_batch_orders uk_batch_order; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rider_delivery_batch_orders
    ADD CONSTRAINT uk_batch_order UNIQUE (batch_id, order_id);


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
-- Name: zone_surge_rules zone_surge_rules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.zone_surge_rules
    ADD CONSTRAINT zone_surge_rules_pkey PRIMARY KEY (id);


--
-- Name: idx_assign_agent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_assign_agent ON public.delivery_assignments USING btree (agent_id, status);


--
-- Name: idx_assign_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_assign_order ON public.delivery_assignments USING btree (order_id);


--
-- Name: idx_delivery_proof_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_delivery_proof_order ON public.order_delivery_proofs USING btree (order_id);


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
-- Name: idx_rider_batch_agent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rider_batch_agent ON public.rider_delivery_batches USING btree (agent_id, status);


--
-- Name: idx_rider_earnings_agent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rider_earnings_agent ON public.rider_earnings USING btree (agent_id);


--
-- Name: idx_rider_location_agent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rider_location_agent ON public.rider_location_updates USING btree (agent_id, recorded_at);


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
-- Name: agent_cod_wallets agent_cod_wallets_agent_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_cod_wallets
    ADD CONSTRAINT agent_cod_wallets_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES public.delivery_agents(id);


--
-- Name: agent_shifts agent_shifts_agent_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_shifts
    ADD CONSTRAINT agent_shifts_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES public.delivery_agents(id);


--
-- Name: delivery_assignments delivery_assignments_agent_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.delivery_assignments
    ADD CONSTRAINT delivery_assignments_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES public.delivery_agents(id);


--
-- Name: saga_steps fk_saga_steps_instance; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saga_steps
    ADD CONSTRAINT fk_saga_steps_instance FOREIGN KEY (saga_instance_id) REFERENCES public.saga_instances(id) ON DELETE CASCADE;


--
-- Name: rider_delivery_batch_orders rider_delivery_batch_orders_batch_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rider_delivery_batch_orders
    ADD CONSTRAINT rider_delivery_batch_orders_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES public.rider_delivery_batches(id) ON DELETE CASCADE;


--
-- Name: rider_delivery_batches rider_delivery_batches_agent_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rider_delivery_batches
    ADD CONSTRAINT rider_delivery_batches_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES public.delivery_agents(id);


--
-- Name: rider_earnings rider_earnings_agent_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rider_earnings
    ADD CONSTRAINT rider_earnings_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES public.delivery_agents(id);


--
-- Name: rider_location_updates rider_location_updates_agent_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rider_location_updates
    ADD CONSTRAINT rider_location_updates_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES public.delivery_agents(id);


--
-- Name: zone_surge_rules zone_surge_rules_zone_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.zone_surge_rules
    ADD CONSTRAINT zone_surge_rules_zone_id_fkey FOREIGN KEY (zone_id) REFERENCES public.delivery_zones(id);


--
-- PostgreSQL database dump complete
--


