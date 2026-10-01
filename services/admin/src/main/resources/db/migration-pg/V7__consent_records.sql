-- ============================================================================
-- V7: consent_records table for GDPR/privacy compliance
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.consent_records (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    purpose character varying(50) NOT NULL,
    granted boolean NOT NULL,
    source character varying(20),
    created_at timestamp(6) without time zone NOT NULL
);
ALTER TABLE public.consent_records ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.consent_records_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE ONLY public.consent_records
    ADD CONSTRAINT consent_records_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.consent_records
    ADD CONSTRAINT uk_consent_user_purpose UNIQUE (user_id, purpose);

CREATE INDEX IF NOT EXISTS idx_consent_user ON public.consent_records USING btree (user_id);