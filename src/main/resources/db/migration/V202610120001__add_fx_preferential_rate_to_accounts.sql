-- V202610120001__add_fx_preferential_rate_to_accounts.sql
-- 계좌별 환전 우대율
--
-- 환전 스프레드를 얼마나 깎아 줄지(0.9 = 스프레드의 90% 를 깎음)를 계좌마다 정한다. 관리자 API 로 바꾼다.
-- NULL 은 "지정하지 않음"이며 이때는 설정(ledger.fx.default-preferential-rate)의 기본 우대율을 쓴다.
-- 0 은 "우대 없음"을 명시한 값이라 NULL 과 다르다.
--
-- 100% 우대(1)는 스프레드 수익이 사라지므로 1 미만으로 제한한다. 애플리케이션 설정에는 더 낮은 상한
-- (ledger.fx.max-preferential-rate, 기본 0.9)이 있고, 이 제약은 그 설정이 틀려도 지켜지는 마지막 방어선이다.

ALTER TABLE public.accounts
    ADD COLUMN fx_preferential_rate numeric(5, 4);

ALTER TABLE public.accounts
    ADD CONSTRAINT chk_accounts_fx_preferential_rate
    CHECK (fx_preferential_rate IS NULL OR (fx_preferential_rate >= 0 AND fx_preferential_rate < 1));
