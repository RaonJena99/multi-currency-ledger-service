-- V4: 글로벌 확장을 위한 Account base_currency 컬럼 추가
ALTER TABLE accounts
ADD COLUMN base_currency VARCHAR(3) DEFAULT 'KRW' NOT NULL;
