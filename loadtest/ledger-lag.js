// 시나리오 4 — 원장 기록 지연: 매수 응답을 받은 뒤 그 거래의 분개가 조회될 때까지 걸리는 시간.
//
// 잔고는 동기로 바뀌지만 분개는 아웃박스(5초 주기 폴링) → Kafka → 컨슈머를 거쳐 기록된다. 이 지연이
// 정합성 점검의 "정리 대기 시간"(기본 10분)과 거래 직후 상세 조회가 404 일 수 있는 시간의 근거다.
import http from 'k6/http';
import { sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { BASE, buy, ok, openFundedAccount, ownerHeaders } from './lib.js';

const lag = new Trend('ledger_lag', true);
const timedOut = new Counter('ledger_lag_timeout');
const POLL_SECONDS = 0.2;
const TIMEOUT_SECONDS = 30;

export const options = {
  scenarios: {
    lag: { executor: 'per-vu-iterations', vus: Number(__ENV.VUS || 5), iterations: Number(__ENV.ITERATIONS || 20),
           maxDuration: '10m' },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const accounts = [];
  for (let i = 0; i < Number(__ENV.VUS || 5); i++) {
    accounts.push(openFundedAccount(100000000));
  }
  return { accounts };
}

export default function (data) {
  // 기록을 확인하자마자 다음 매수를 보내면 매수 시각이 릴레이 폴링 직후에 고정되어 지연이 늘 5초 가까이 나온다.
  // 폴링 주기(5초) 안의 임의 시점에 매수해야 실제 사용자가 겪는 지연 분포가 된다.
  sleep(Math.random() * 5);
  const accountId = data.accounts[(__VU - 1) % data.accounts.length];
  const response = buy(accountId, '0.0001');
  if (!ok(response, 'buy')) {
    return;
  }
  const tradeId = response.json('tradeId');
  const committedAt = Date.now();

  for (let waited = 0; waited < TIMEOUT_SECONDS; waited += POLL_SECONDS) {
    const detail = http.get(`${BASE}/api/v1/accounts/${accountId}/transactions/${tradeId}`,
      { headers: ownerHeaders(accountId), tags: { name: 'transaction_detail' }, responseCallback: http.expectedStatuses(200, 404) });
    if (detail.status === 200) {
      lag.add(Date.now() - committedAt);
      return;
    }
    sleep(POLL_SECONDS);
  }
  timedOut.add(1);
}
