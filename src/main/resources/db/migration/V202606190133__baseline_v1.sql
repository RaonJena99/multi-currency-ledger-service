--
-- PostgreSQL database dump
--

\restrict 23vdDpSknocco38PmpeMIFnSyE6rAY9bTbhqMfsPUT07N12ywMxOfrJhxACBYwd

-- Dumped from database version 15.18
-- Dumped by pg_dump version 15.18

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: update_updated_at_column(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_updated_at_column() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: account_balances; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.account_balances (
    id bigint NOT NULL,
    account_id uuid NOT NULL,
    asset_code character varying(20) NOT NULL,
    balance numeric(36,18) DEFAULT 0 NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    average_unit_price numeric(36,18) DEFAULT 0 NOT NULL,
    asset_type character varying(20) DEFAULT 'CRYPTO'::character varying NOT NULL,
    average_unit_price_asset_type character varying(20) DEFAULT 'FIAT'::character varying NOT NULL
);


--
-- Name: account_balances_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.account_balances_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: account_balances_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.account_balances_id_seq OWNED BY public.account_balances.id;


--
-- Name: accounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.accounts (
    id uuid NOT NULL,
    owner_name character varying(100) NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: market_data; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.market_data (
    base_asset_code character varying(20) NOT NULL,
    quote_asset_code character varying(20) NOT NULL,
    current_market_price numeric(36,18) NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: monthly_account_ledgers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.monthly_account_ledgers (
    id bigint NOT NULL,
    account_id uuid NOT NULL,
    asset_code character varying(20) NOT NULL,
    ledger_month character varying(7) NOT NULL,
    balance numeric(36,18) NOT NULL,
    asset_type character varying(20) NOT NULL,
    average_unit_price numeric(36,18) NOT NULL,
    average_unit_price_asset_type character varying(20) NOT NULL,
    carried_forward boolean DEFAULT false NOT NULL,
    version bigint DEFAULT 0,
    created_at timestamp with time zone,
    updated_at timestamp with time zone
);


--
-- Name: current_portfolio_view; Type: MATERIALIZED VIEW; Schema: public; Owner: -
--

CREATE MATERIALIZED VIEW public.current_portfolio_view AS
 SELECT DISTINCT ON (mal.account_id, mal.asset_code) md5(((mal.account_id)::text || (mal.asset_code)::text)) AS id,
    mal.account_id,
    mal.asset_code,
    mal.balance AS total_quantity,
    mal.average_unit_price AS avg_unit_price,
    COALESCE(md.current_market_price, (0)::numeric) AS current_market_price,
    ((COALESCE(md.current_market_price, (0)::numeric) - mal.average_unit_price) * mal.balance) AS unrealized_pnl,
    mal.ledger_month AS last_updated_month
   FROM (public.monthly_account_ledgers mal
     LEFT JOIN public.market_data md ON ((((mal.asset_code)::text = (md.base_asset_code)::text) AND ((mal.average_unit_price_asset_type)::text = (md.quote_asset_code)::text))))
  ORDER BY mal.account_id, mal.asset_code, mal.ledger_month DESC
  WITH NO DATA;


--
-- Name: external_settlement; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.external_settlement (
    id character varying(36) NOT NULL,
    external_reference_id character varying(100) NOT NULL,
    institution_code character varying(20) NOT NULL,
    settlement_date timestamp with time zone NOT NULL,
    description character varying(255) NOT NULL,
    amount numeric(27,18) NOT NULL,
    asset_type character varying(20) NOT NULL,
    currency_code character varying(3) NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    matched_internal_transaction_id character varying(36),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
)
PARTITION BY RANGE (settlement_date);


--
-- Name: monthly_account_ledgers_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.monthly_account_ledgers_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: monthly_account_ledgers_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.monthly_account_ledgers_id_seq OWNED BY public.monthly_account_ledgers.id;


--
-- Name: outbox_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.outbox_events (
    id bigint NOT NULL,
    aggregate_type character varying(255) NOT NULL,
    aggregate_id character varying(255) NOT NULL,
    event_type character varying(255) NOT NULL,
    payload text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    processed boolean DEFAULT false NOT NULL,
    retry_count integer DEFAULT 0 NOT NULL,
    error_message character varying(500),
    dead_letter boolean DEFAULT false NOT NULL
);


--
-- Name: outbox_events_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.outbox_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: outbox_events_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.outbox_events_id_seq OWNED BY public.outbox_events.id;


--
-- Name: portfolio_summary_view; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.portfolio_summary_view AS
 SELECT ab.account_id,
    ab.asset_code AS base_asset,
    ab.average_unit_price_asset_type AS quote_asset,
    ab.balance AS current_quantity,
    ab.average_unit_price AS cost_basis,
    md.current_market_price,
    ((md.current_market_price - ab.average_unit_price) * ab.balance) AS unrealized_pnl
   FROM (public.account_balances ab
     LEFT JOIN public.market_data md ON ((((ab.asset_code)::text = (md.base_asset_code)::text) AND ((ab.average_unit_price_asset_type)::text = (md.quote_asset_code)::text))));


--
-- Name: reconciliation_dead_letter; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.reconciliation_dead_letter (
    id bigint NOT NULL,
    external_settlement_id character varying(36) NOT NULL,
    failure_reason character varying(30) NOT NULL,
    error_message character varying(500) NOT NULL,
    is_resolved boolean DEFAULT false NOT NULL,
    resolved_at timestamp with time zone,
    handler_enrichment_payload jsonb,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: reconciliation_dead_letter_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.reconciliation_dead_letter_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: reconciliation_dead_letter_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.reconciliation_dead_letter_id_seq OWNED BY public.reconciliation_dead_letter.id;


--
-- Name: transaction_entries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.transaction_entries (
    id bigint NOT NULL,
    transaction_id uuid NOT NULL,
    account_id uuid NOT NULL,
    asset_code character varying(20) NOT NULL,
    quantity_asset_type character varying(20) NOT NULL,
    quantity numeric(36,18) NOT NULL,
    unit_price numeric(36,18) NOT NULL,
    exchange_rate numeric(19,6) DEFAULT 1.0,
    amount numeric(36,18) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    entry_type character varying(10) NOT NULL,
    realized_pnl numeric(36,18) DEFAULT 0,
    unit_price_asset_type character varying(20) DEFAULT 'FIAT'::character varying NOT NULL,
    amount_asset_type character varying(20) DEFAULT 'FIAT'::character varying NOT NULL,
    realized_pnl_asset_type character varying(20) DEFAULT 'FIAT'::character varying NOT NULL,
    CONSTRAINT chk_amount_calculation CHECK ((amount = ((quantity * unit_price) * exchange_rate))),
    CONSTRAINT chk_entry_type CHECK (((entry_type)::text = ANY ((ARRAY['DEBIT'::character varying, 'CREDIT'::character varying])::text[])))
);


--
-- Name: transaction_entries_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.transaction_entries_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: transaction_entries_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.transaction_entries_id_seq OWNED BY public.transaction_entries.id;


--
-- Name: transactions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.transactions (
    id uuid NOT NULL,
    transaction_type character varying(30) NOT NULL,
    description character varying(255) NOT NULL,
    transacted_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: account_balances id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_balances ALTER COLUMN id SET DEFAULT nextval('public.account_balances_id_seq'::regclass);


--
-- Name: monthly_account_ledgers id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monthly_account_ledgers ALTER COLUMN id SET DEFAULT nextval('public.monthly_account_ledgers_id_seq'::regclass);


--
-- Name: outbox_events id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outbox_events ALTER COLUMN id SET DEFAULT nextval('public.outbox_events_id_seq'::regclass);


--
-- Name: reconciliation_dead_letter id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reconciliation_dead_letter ALTER COLUMN id SET DEFAULT nextval('public.reconciliation_dead_letter_id_seq'::regclass);


--
-- Name: transaction_entries id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transaction_entries ALTER COLUMN id SET DEFAULT nextval('public.transaction_entries_id_seq'::regclass);


--
-- Name: account_balances account_balances_account_id_asset_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_balances
    ADD CONSTRAINT account_balances_account_id_asset_code_key UNIQUE (account_id, asset_code);


--
-- Name: account_balances account_balances_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_balances
    ADD CONSTRAINT account_balances_pkey PRIMARY KEY (id);


--
-- Name: accounts accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.accounts
    ADD CONSTRAINT accounts_pkey PRIMARY KEY (id);


--
-- Name: market_data market_data_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.market_data
    ADD CONSTRAINT market_data_pkey PRIMARY KEY (base_asset_code, quote_asset_code);


--
-- Name: monthly_account_ledgers monthly_account_ledgers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monthly_account_ledgers
    ADD CONSTRAINT monthly_account_ledgers_pkey PRIMARY KEY (id);


--
-- Name: outbox_events outbox_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outbox_events
    ADD CONSTRAINT outbox_events_pkey PRIMARY KEY (id);


--
-- Name: external_settlement pk_external_settlement; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.external_settlement
    ADD CONSTRAINT pk_external_settlement PRIMARY KEY (id, settlement_date);


--
-- Name: reconciliation_dead_letter pk_reconciliation_dead_letter; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reconciliation_dead_letter
    ADD CONSTRAINT pk_reconciliation_dead_letter PRIMARY KEY (id);


--
-- Name: transaction_entries transaction_entries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transaction_entries
    ADD CONSTRAINT transaction_entries_pkey PRIMARY KEY (id);


--
-- Name: transactions transactions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transactions
    ADD CONSTRAINT transactions_pkey PRIMARY KEY (id);


--
-- Name: account_balances uk_account_asset; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_balances
    ADD CONSTRAINT uk_account_asset UNIQUE (account_id, asset_code);


--
-- Name: monthly_account_ledgers uk_account_asset_month; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monthly_account_ledgers
    ADD CONSTRAINT uk_account_asset_month UNIQUE (account_id, asset_code, ledger_month);


--
-- Name: idx_balance_account; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_balance_account ON public.account_balances USING btree (account_id);


--
-- Name: idx_current_portfolio_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_current_portfolio_unique ON public.current_portfolio_view USING btree (account_id, asset_code);


--
-- Name: idx_dlq_external_settlement_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dlq_external_settlement_id ON public.reconciliation_dead_letter USING btree (external_settlement_id);


--
-- Name: idx_dlq_unresolved_tracker; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dlq_unresolved_tracker ON public.reconciliation_dead_letter USING btree (failure_reason, created_at DESC) WHERE (is_resolved = false);


--
-- Name: idx_entry_account_asset; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_entry_account_asset ON public.transaction_entries USING btree (account_id, asset_code);


--
-- Name: idx_entry_transaction_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_entry_transaction_id ON public.transaction_entries USING btree (transaction_id);


--
-- Name: idx_monthly_ledger_search; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_monthly_ledger_search ON public.monthly_account_ledgers USING btree (account_id, asset_code, ledger_month DESC);


--
-- Name: idx_outbox_unprocessed; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_outbox_unprocessed ON public.outbox_events USING btree (created_at) WHERE ((processed = false) AND (dead_letter = false));


--
-- Name: idx_settlement_date_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_settlement_date_status ON ONLY public.external_settlement USING btree (settlement_date, status);


--
-- Name: uq_institution_external_ref; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_institution_external_ref ON ONLY public.external_settlement USING btree (institution_code, external_reference_id, settlement_date);


--
-- Name: account_balances update_account_balances_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_account_balances_updated_at BEFORE UPDATE ON public.account_balances FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: accounts update_accounts_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_accounts_updated_at BEFORE UPDATE ON public.accounts FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: account_balances account_balances_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_balances
    ADD CONSTRAINT account_balances_account_id_fkey FOREIGN KEY (account_id) REFERENCES public.accounts(id);


--
-- Name: transaction_entries transaction_entries_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transaction_entries
    ADD CONSTRAINT transaction_entries_account_id_fkey FOREIGN KEY (account_id) REFERENCES public.accounts(id);


--
-- Name: transaction_entries transaction_entries_transaction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transaction_entries
    ADD CONSTRAINT transaction_entries_transaction_id_fkey FOREIGN KEY (transaction_id) REFERENCES public.transactions(id);


--
-- PostgreSQL database dump complete
--

\unrestrict 23vdDpSknocco38PmpeMIFnSyE6rAY9bTbhqMfsPUT07N12ywMxOfrJhxACBYwd

