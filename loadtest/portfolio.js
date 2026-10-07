// 시나리오 3 — 포트폴리오 조회: 캐시가 찬 상태와, 입금 직후(캐시를 막 지운 상태)의 지연을 비교한다.
//
// 거래·입출금이 커밋되면 캐시를 지우고 세대를 올린다. 입금 직후 조회는 캐시가 비어 있거나 비동기 갱신이 막
// 채운 상태라, 실제 사용자가 "거래하고 바로 잔고를 보는" 경로의 지연에 가깝다.
import { Trend } from 'k6/metrics';
import { buy, deposit, getPortfolio, ok, openFundedAccount } from './lib.js';

const warm = new Trend('portfolio_warm', true);
const afterWrite = new Trend('portfolio_after_write', true);
const VUS = Number(__ENV.VUS || 10);

export const options = {
  scenarios: {
    warm: { executor: 'constant-vus', vus: VUS, duration: '30s', exec: 'readWarm' },
    after_write: { executor: 'constant-vus', vus: VUS, duration: '30s', exec: 'readAfterWrite', startTime: '35s' },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

// 평가할 자산이 둘(KRW, BTC)이 되도록 BTC 를 조금 사 둔다.
function prepareAccount() {
  const accountId = openFundedAccount(100000000);
  buy(accountId, '0.01');
  return accountId;
}

export function setup() {
  return { warmAccount: prepareAccount() };
}

// VU 마다 자기 계좌. __VU 는 테스트 전체의 VU 번호라, 시나리오가 받은 VU 가 연속된 번호라는 보장이 없다.
// 번호로 계좌를 나누면 두 VU 가 같은 계좌에 동시에 입금해 낙관적 락 충돌(409)이 섞인다.
let ownAccount;

export function readWarm(data) {
  const response = getPortfolio(data.warmAccount, { name: 'portfolio' });
  ok(response, 'portfolio');
  warm.add(response.timings.duration);
}

export function readAfterWrite() {
  if (!ownAccount) {
    ownAccount = prepareAccount();
    return;
  }
  const accountId = ownAccount;
  ok(deposit(accountId, 1000), 'deposit');
  const response = getPortfolio(accountId, { name: 'portfolio' });
  ok(response, 'portfolio');
  afterWrite.add(response.timings.duration);
}
