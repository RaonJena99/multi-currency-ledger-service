-- V202610070001__create_realized_pnl_entries.sql
-- 실현 손익 분개 백필
--
-- 실현 손익은 지금까지 대변 분개의 realized_pnl 컬럼에만 있었고, 대차는 `차변 = 대변 + 실현 손익` 이었습니다.
-- 이제 손익은 고객 계정의 별도 분개(REALIZED_PNL_FX / REALIZED_PNL_TRADING)로 기록하고 대차는 `차변 = 대변`
-- 입니다. 이전에 기록된 분개도 같은 규칙을 따르도록, realized_pnl 이 0 이 아닌 분개마다 손익 분개를 추가합니다.
--
--   이익 (realized_pnl > 0)  고객 계정 대변
--   손실 (realized_pnl < 0)  고객 계정 차변
--
-- 종류는 새 코드와 같은 규칙으로 정합니다. 나가는 쪽(손익을 만든 분개)이 법정화폐면 환차손익(FX), 그 밖의
-- 자산이면 매매 손익(TRADING) 입니다.
--
-- 기존 행은 수정하지 않고 분개만 더합니다. realized_pnl 컬럼은 그 분개가 만든 손익을 보여 주는 참고값으로 남고
-- 대차 계산에는 더 이상 쓰이지 않습니다.
--
-- 이미 손익 분개가 있는 거래(새 코드가 기록한 거래)는 건너뜁니다. 그래서 다시 실행해도 분개가 늘지 않습니다.
-- 앱 인스턴스를 멈췄다 띄우는 배포를 전제로 합니다. 롤링 배포 중 옛 인스턴스가 이 마이그레이션 뒤에 기록한
-- 분개는 손익 분개가 없으므로, 이 INSERT 를 다시 실행해 보정합니다.

INSERT INTO public.transaction_entries
    (transaction_id, account_id, entry_type, asset_code, quantity_asset_type, quantity, quantity_currency,
     unit_price, exchange_rate, amount, amount_asset_type, amount_currency,
     realized_pnl, realized_pnl_asset_type, realized_pnl_currency, created_at)
SELECT te.transaction_id,
       te.account_id,
       CASE WHEN te.realized_pnl > 0 THEN 'CREDIT' ELSE 'DEBIT' END,
       CASE WHEN te.quantity_asset_type = 'FIAT' THEN 'REALIZED_PNL_FX' ELSE 'REALIZED_PNL_TRADING' END,
       'FIAT', abs(te.realized_pnl), te.amount_currency,
       1, 1, abs(te.realized_pnl), 'FIAT', te.amount_currency,
       0, 'FIAT', te.amount_currency, te.created_at
FROM public.transaction_entries te
WHERE te.realized_pnl IS NOT NULL
  AND te.realized_pnl <> 0
  AND NOT EXISTS (SELECT 1 FROM public.transaction_entries p
                  WHERE p.transaction_id = te.transaction_id
                    AND p.asset_code IN ('REALIZED_PNL_FX', 'REALIZED_PNL_TRADING'))
ORDER BY te.id;
