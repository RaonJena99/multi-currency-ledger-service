// 시나리오 2 — 락 경합: 한 계좌에 동시에 매수를 몰아넣는다.
//
// 같은 원장 행(KRW, BTC)을 동시에 고치므로 낙관적 락(@Version) 충돌이 난다. 충돌하면 서비스가 최대 3회
// 재시도하고(100ms → 200ms 백오프), 그래도 실패하면 409 CONCURRENCY_CONFLICT 다. 결과별 건수와 지연으로
// "재시도가 경합을 얼마나 흡수하는가"를 본다.
import { Counter, Trend } from 'k6/metrics';
import { buy, openFundedAccount } from './lib.js';

const succeeded = new Counter('buy_ok');
const conflicted = new Counter('buy_conflict');
const otherFailures = new Counter('buy_other_failure');
const okLatency = new Trend('buy_ok_duration', true);
const conflictLatency = new Trend('buy_conflict_duration', true);

export const options = {
  scenarios: {
    contention: { executor: 'constant-vus', vus: Number(__ENV.VUS || 20), duration: __ENV.DURATION || '30s' },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { accountId: openFundedAccount(1000000000) };
}

export default function (data) {
  const response = buy(data.accountId, '0.0001');
  if (response.status === 200) {
    succeeded.add(1);
    okLatency.add(response.timings.duration);
  } else if (response.status === 409 && response.json('code') === 'CONCURRENCY_CONFLICT') {
    conflicted.add(1);
    conflictLatency.add(response.timings.duration);
  } else {
    otherFailures.add(1, { status: String(response.status) });
  }
}
