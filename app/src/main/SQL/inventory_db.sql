--
-- PostgreSQL database dump
--

\restrict wmc0Zhdt4Mm9rNeUdd68KbtSjIv8UFI2tky2cqJD3rZCg36bFQ4Hps9GawJXHfH

-- Dumped from database version 18.4
-- Dumped by pg_dump version 18.4

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: item_master; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.item_master (
    item_id integer NOT NULL,
    item_name character varying(100) NOT NULL,
    category character varying(20) NOT NULL,
    usd_price numeric(12,4) DEFAULT 0.0000 NOT NULL,
    exchange_rate numeric(8,4) DEFAULT 1.0000 NOT NULL,
    tax_coefficient numeric(6,4) DEFAULT 0.0000 NOT NULL,
    last_update_date date DEFAULT CURRENT_DATE NOT NULL,
    last_update_operator_id integer,
    CONSTRAINT item_master_category_check CHECK (((category)::text = ANY ((ARRAY['Main'::character varying, 'Accessories'::character varying])::text[])))
);


ALTER TABLE public.item_master OWNER TO postgres;

--
-- Name: item_master_item_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

ALTER TABLE public.item_master ALTER COLUMN item_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.item_master_item_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: operator_master; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.operator_master (
    operator_id integer NOT NULL,
    operator_name character varying(50) NOT NULL,
    password_hash character varying(255) NOT NULL,
    is_admin boolean DEFAULT false,
    create_date date DEFAULT CURRENT_DATE NOT NULL
);


ALTER TABLE public.operator_master OWNER TO postgres;

--
-- Name: operator_master_operator_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

ALTER TABLE public.operator_master ALTER COLUMN operator_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.operator_master_operator_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: stock_master; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.stock_master (
    item_id integer NOT NULL,
    current_qty integer DEFAULT 0 NOT NULL,
    last_update_date date DEFAULT CURRENT_DATE NOT NULL,
    last_update_time time without time zone DEFAULT CURRENT_TIME NOT NULL,
    CONSTRAINT stock_master_current_qty_check CHECK ((current_qty >= 0))
);


ALTER TABLE public.stock_master OWNER TO postgres;

--
-- Name: stock_transactions; Type: TABLE; Schema: public; Owner: postgres
--

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
    CONSTRAINT stock_transactions_io_type_check CHECK (((io_type)::text = ANY ((ARRAY['IN'::character varying, 'OUT'::character varying])::text[]))),
    CONSTRAINT stock_transactions_transaction_qty_check CHECK ((transaction_qty > 0))
);


ALTER TABLE public.stock_transactions OWNER TO postgres;

--
-- Name: stock_transactions_tran_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

ALTER TABLE public.stock_transactions ALTER COLUMN tran_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.stock_transactions_tran_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: item_master item_master_item_name_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.item_master
    ADD CONSTRAINT item_master_item_name_key UNIQUE (item_name);


--
-- Name: item_master item_master_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.item_master
    ADD CONSTRAINT item_master_pkey PRIMARY KEY (item_id);


--
-- Name: operator_master operator_master_operator_name_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.operator_master
    ADD CONSTRAINT operator_master_operator_name_key UNIQUE (operator_name);


--
-- Name: operator_master operator_master_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.operator_master
    ADD CONSTRAINT operator_master_pkey PRIMARY KEY (operator_id);


--
-- Name: stock_master stock_master_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.stock_master
    ADD CONSTRAINT stock_master_pkey PRIMARY KEY (item_id);


--
-- Name: stock_transactions stock_transactions_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.stock_transactions
    ADD CONSTRAINT stock_transactions_pkey PRIMARY KEY (tran_id);


--
-- Name: stock_transactions unique_date_time_item; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.stock_transactions
    ADD CONSTRAINT unique_date_time_item UNIQUE (transaction_date, transaction_time, item_id);


--
-- Name: idx_transactions_query; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_transactions_query ON public.stock_transactions USING btree (item_id, transaction_date DESC, transaction_time DESC);


--
-- Name: item_master item_master_last_update_operator_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.item_master
    ADD CONSTRAINT item_master_last_update_operator_id_fkey FOREIGN KEY (last_update_operator_id) REFERENCES public.operator_master(operator_id);


--
-- Name: stock_master stock_master_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.stock_master
    ADD CONSTRAINT stock_master_item_id_fkey FOREIGN KEY (item_id) REFERENCES public.item_master(item_id) ON DELETE CASCADE;


--
-- Name: stock_transactions stock_transactions_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.stock_transactions
    ADD CONSTRAINT stock_transactions_item_id_fkey FOREIGN KEY (item_id) REFERENCES public.item_master(item_id) ON DELETE RESTRICT;


--
-- PostgreSQL database dump complete
--

\unrestrict wmc0Zhdt4Mm9rNeUdd68KbtSjIv8UFI2tky2cqJD3rZCg36bFQ4Hps9GawJXHfH