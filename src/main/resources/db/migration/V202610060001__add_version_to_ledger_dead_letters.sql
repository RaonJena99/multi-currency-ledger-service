-- V202610060001__add_version_to_ledger_dead_letters.sql
-- 원장 데드레터 재처리·해결 API 에 낙관적 락을 적용합니다.
--
-- 두 운영자가 같은 데드레터를 동시에 처리하면, 버전 없이는 둘 다 "해결" 응답을 받아
-- 누가 실제로 처리했는지 알 수 없습니다. 나중에 커밋하는 쪽을 충돌(409)로 돌려보내기 위한 컬럼입니다.

ALTER TABLE public.ledger_dead_letters
    ADD COLUMN version bigint NOT NULL DEFAULT 0;
