// 시나리오 1 — 처리량: 서로 다른 계좌 N 개에서 매수·매도를 반복한다.
//
// 계좌가 겹치지 않으므로 낙관적 락 충돌이 거의 없다. "락 경합이 없을 때 거래 API 가 얼마나 처리하는가"의 기준선이다.
import { buy, sell, ok, openFundedAccount } from './lib.js';

const VUS = Number(__ENV.VUS || 20);

export const options = {
  scenarios: {
    trades: { executor: 'constant-vus', vus: VUS, duration: __ENV.DURATION || '60s' },
  },
  thresholds: {
    'http_req_failed{name:buy}': ['rate<0.01'],
    'http_req_failed{name:sell}': ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  // VU 마다 계좌 하나. 1억 원이면 0.0001 BTC(1만 원) 매수를 수천 번 해도 남는다.
  const accounts = [];
  for (let i = 0; i < VUS; i++) {
    accounts.push(openFundedAccount(100000000));
  }
  return { accounts };
}

export default function (data) {
  const accountId = data.accounts[(__VU - 1) % data.accounts.length];
  ok(buy(accountId, '0.0001'), 'buy');
  ok(sell(accountId, '0.0001'), 'sell');
}
