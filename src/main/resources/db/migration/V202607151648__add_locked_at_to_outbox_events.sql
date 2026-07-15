-- OutboxEvent의 다중 인스턴스 중복 처리 방지를 위한 선점용 컬럼 추가
ALTER TABLE outbox_events ADD COLUMN locked_at TIMESTAMPTZ;

-- 필요 시 조회 최적화를 위해 인덱스를 추가할 수도 있지만, 
-- 현재 100건 단위의 제한된 폴링이므로 일단 컬럼만 추가합니다.
