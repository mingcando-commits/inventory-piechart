-- ============================================================================
-- Test database schema -- adapted from recreate_inventory_schema.sql (which
-- was built from a real pg_dump against production). RLS statements are
-- omitted here since they're irrelevant for a local test Postgres connected
-- to as its owner (which bypasses RLS anyway) -- everything else matches
-- production exactly.
-- ============================================================================

CREATE TABLE public.operator_master (
    operator_id integer NOT NULL,
    operator_name character varying(50) NOT NULL,
    password_hash character varying(255) NOT NULL,
    is_admin boolean DEFAULT false,
    create_date date DEFAULT CURRENT_DATE NOT NULL
);

ALTER TABLE public.operator_master ALTER COLUMN operator_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.operator_master_operator_id_seq
    START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1
);


CREATE TABLE public.item_master (
    item_id integer NOT NULL,
    item_name character varying(100) NOT NULL,
    category character varying(20) NOT NULL,
    usd_price numeric(12,4) DEFAULT 0.0000 NOT NULL,
    exchange_rate numeric(8,4),
    tax_coefficient numeric(6,4),
    last_update_date date DEFAULT CURRENT_DATE NOT NULL,
    last_update_operator_id integer,
    CONSTRAINT item_master_category_check CHECK (
        ((category)::text = ANY (ARRAY[('Main'::character varying)::text, ('Accessories'::character varying)::text]))
    )
);

ALTER TABLE public.item_master ALTER COLUMN item_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.item_master_item_id_seq
    START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1
);


CREATE TABLE public.stock_master (
    item_id integer NOT NULL,
    current_qty integer DEFAULT 0 NOT NULL,
    last_update_date date DEFAULT CURRENT_DATE NOT NULL,
    last_update_time time without time zone DEFAULT CURRENT_TIME NOT NULL,
    CONSTRAINT stock_master_current_qty_check CHECK ((current_qty >= 0))
);


CREATE TABLE public.stock_transactions (
    tran_id bigint NOT NULL,
    transaction_date date DEFAULT CURRENT_DATE NOT NULL,
    transaction_time time without time zone DEFAULT CURRENT_TIME NOT NULL,
    item_id integer,
    io_type character varying(3) NOT NULL,
    transaction_qty integer NOT NULL,
    post_balance_qty integer NOT NULL,
    remark text,
    operator_id integer,
    CONSTRAINT stock_transactions_io_type_check CHECK (
        ((io_type)::text = ANY (ARRAY[('IN'::character varying)::text, ('OUT'::character varying)::text]))
    ),
    CONSTRAINT stock_transactions_transaction_qty_check CHECK ((transaction_qty > 0))
);

ALTER TABLE public.stock_transactions ALTER COLUMN tran_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.stock_transactions_tran_id_seq
    START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1
);


CREATE TABLE public.system_settings (
    id smallint DEFAULT 1 NOT NULL,
    global_exchange_rate numeric(8,4) NOT NULL,
    global_tax_coefficient numeric(6,4) NOT NULL,
    last_update_date date DEFAULT CURRENT_DATE,
    last_update_operator_id integer,
    CONSTRAINT system_settings_single_row CHECK ((id = 1))
);


ALTER TABLE ONLY public.item_master ADD CONSTRAINT item_master_item_name_key UNIQUE (item_name);
ALTER TABLE ONLY public.item_master ADD CONSTRAINT item_master_pkey PRIMARY KEY (item_id);
ALTER TABLE ONLY public.operator_master ADD CONSTRAINT operator_master_operator_name_key UNIQUE (operator_name);
ALTER TABLE ONLY public.operator_master ADD CONSTRAINT operator_master_pkey PRIMARY KEY (operator_id);
ALTER TABLE ONLY public.stock_master ADD CONSTRAINT stock_master_pkey PRIMARY KEY (item_id);
ALTER TABLE ONLY public.stock_transactions ADD CONSTRAINT stock_transactions_pkey PRIMARY KEY (tran_id);
ALTER TABLE ONLY public.system_settings ADD CONSTRAINT system_settings_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.stock_transactions ADD CONSTRAINT unique_date_time_item UNIQUE (transaction_date, transaction_time, item_id);

CREATE INDEX idx_stock_transactions_item_id_date
    ON public.stock_transactions USING btree (item_id, transaction_date DESC, transaction_time DESC, tran_id DESC);
CREATE INDEX idx_transactions_query
    ON public.stock_transactions USING btree (item_id, transaction_date DESC, transaction_time DESC);

ALTER TABLE ONLY public.item_master
    ADD CONSTRAINT item_master_last_update_operator_id_fkey
    FOREIGN KEY (last_update_operator_id) REFERENCES public.operator_master(operator_id);
ALTER TABLE ONLY public.stock_master
    ADD CONSTRAINT stock_master_item_id_fkey
    FOREIGN KEY (item_id) REFERENCES public.item_master(item_id) ON DELETE CASCADE;
ALTER TABLE ONLY public.stock_transactions
    ADD CONSTRAINT stock_transactions_item_id_fkey
    FOREIGN KEY (item_id) REFERENCES public.item_master(item_id) ON DELETE RESTRICT;
ALTER TABLE ONLY public.system_settings
    ADD CONSTRAINT system_settings_last_update_operator_id_fkey
    FOREIGN KEY (last_update_operator_id) REFERENCES public.operator_master(operator_id);
