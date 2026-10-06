-- V202610060006__add_processed_at_to_outbox_events.sql
-- 아웃박스 이벤트의 발행 시각
--
-- 발행이 끝난 행을 보존 기간이 지나면 지우기 위한 기준 시각입니다. 만든 시각(created_at)을 기준으로 하면
-- 재시도·재발행 끝에 늦게 발행된 행이 발행 직후 지워져, 장애를 조사할 흔적이 사라집니다.
-- 기존 발행 완료 행은 발행 시각을 알 수 없으므로 만든 시각으로 채웁니다.

ALTER TABLE public.outbox_events
    ADD COLUMN processed_at timestamp with time zone;

UPDATE public.outbox_events
SET processed_at = created_at
WHERE processed = true;

-- 정리 작업이 찾는 행(발행 완료, 데드레터 아님)만 담는 부분 인덱스
CREATE INDEX idx_outbox_processed_at
    ON public.outbox_events (processed_at)
    WHERE processed = true AND dead_letter = false;
