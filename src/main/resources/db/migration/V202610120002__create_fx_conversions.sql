-- V202610120002__create_fx_conversions.sql
-- 환전 체결 기록
--
-- 견적(Redis, 몇십 초만 유효)을 체결하면 그 조건을 여기에 남긴다. 대사·감사에서 "이 고객에게 왜 이 환율이 적용됐는가"를
-- 답하는 근거이며, 거래마다 시세·고객 환율·스프레드율·우대율과 회사 쪽 시세 가치·스프레드 수익을 함께 저장한다.
--
-- id 는 원장 거래(transactions.id)와 같은 값이다. 체결 트랜잭션이 이 ID 로 원장 기록 이벤트를 발행하므로 두 기록을 ID 하나로
-- 잇는다. 원장은 비동기로 기록되므로 외래키는 걸지 않는다.
--
-- quote_id 유니크 제약이 견적의 단일 사용을 보장한다. 같은 견적으로 멱등성 키만 바꿔 다시 체결하는 요청과, 동시에 들어온 두
-- 요청 중 하나를 DB 가 막는다. Redis 에서 견적을 지우는 것은 그 뒤의 정리일 뿐이다.

CREATE TABLE public.fx_conversions (
    id                uuid                     NOT NULL,
    quote_id          uuid                     NOT NULL,
    account_id        uuid                     NOT NULL,
    direction         character varying(20)    NOT NULL,
    from_currency     character varying(10)    NOT NULL,
    from_amount       numeric(36, 18)          NOT NULL,
    to_currency       character varying(10)    NOT NULL,
    to_amount         numeric(36, 18)          NOT NULL,
    base_currency     character varying(10)    NOT NULL,
    market_rate       numeric(36, 18)          NOT NULL,
    customer_rate     numeric(36, 18)          NOT NULL,
    spread_rate       numeric(36, 18)          NOT NULL,
    preferential_rate numeric(5, 4)            NOT NULL,
    market_value      numeric(36, 18)          NOT NULL,
    spread_revenue    numeric(36, 18)          NOT NULL,
    converted_at      timestamp with time zone NOT NULL,
    CONSTRAINT fx_conversions_pkey PRIMARY KEY (id),
    CONSTRAINT uq_fx_conversions_quote_id UNIQUE (quote_id),
    CONSTRAINT fx_conversions_account_id_fkey FOREIGN KEY (account_id) REFERENCES public.accounts (id),
    CONSTRAINT chk_fx_conversions_direction CHECK (direction IN ('BUY_FOREIGN', 'SELL_FOREIGN')),
    CONSTRAINT chk_fx_conversions_amounts_positive CHECK (from_amount > 0 AND to_amount > 0),
    CONSTRAINT chk_fx_conversions_rates_positive CHECK (market_rate > 0 AND customer_rate > 0),
    CONSTRAINT chk_fx_conversions_spread_revenue CHECK (spread_revenue >= 0),
    CONSTRAINT chk_fx_conversions_preferential_rate CHECK (preferential_rate >= 0 AND preferential_rate < 1)
);

CREATE INDEX idx_fx_conversions_account_converted_at ON public.fx_conversions (account_id, converted_at DESC);
