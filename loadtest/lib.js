// 부하 테스트 공용 함수. 관리자 API 로 계좌를 열고 입금한 뒤, 계좌 소유자로 거래한다.
//
// 인증은 게이트웨이가 넣어 주는 헤더를 그대로 흉내 낸다(X-Auth-*, X-Gateway-Secret).
import http from 'k6/http';
import { check, fail } from 'k6';

export const BASE = __ENV.BASE_URL || 'http://app:8080';
const SECRET = __ENV.GATEWAY_SECRET || '';

// dev 프로필의 더미 시세는 1 BTC = 100,000,000 KRW 다. 단가가 시세에서 2% 넘게 벗어나면 거부된다.
export const BTC_PRICE_KRW = 100000000;

export function adminHeaders() {
  return {
    'Content-Type': 'application/json',
    'X-Gateway-Secret': SECRET,
    'X-Auth-Subject': 'loadtest-admin',
    'X-Auth-Roles': 'ADMIN',
  };
}

export function ownerHeaders(accountId) {
  return {
    'Content-Type': 'application/json',
    'X-Gateway-Secret': SECRET,
    'X-Auth-Subject': `loadtest-${accountId}`,
    'X-Auth-Account-Id': accountId,
  };
}

/** 계좌를 열고 원화를 입금한다. setup() 에서만 쓴다. */
export function openFundedAccount(krw) {
  const accountId = crypto.randomUUID();
  const opened = http.post(`${BASE}/api/v1/admin/accounts`,
    JSON.stringify({ accountId, ownerName: 'loadtest', baseCurrency: 'KRW' }),
    { headers: adminHeaders(), tags: { name: 'setup' } });
  const deposited = http.post(`${BASE}/api/v1/admin/accounts/${accountId}/deposits`,
    JSON.stringify({ idempotencyKey: crypto.randomUUID(), currency: 'KRW', amount: krw }),
    { headers: adminHeaders(), tags: { name: 'setup' } });
  if (opened.status !== 201 || deposited.status !== 200) {
    fail(`계좌 준비 실패: open=${opened.status} deposit=${deposited.status} ${deposited.body}`);
  }
  return accountId;
}

export function deposit(accountId, krw) {
  return http.post(`${BASE}/api/v1/admin/accounts/${accountId}/deposits`,
    JSON.stringify({ idempotencyKey: crypto.randomUUID(), currency: 'KRW', amount: krw }),
    { headers: adminHeaders(), tags: { name: 'deposit' } });
}

function trade(side, accountId, quantity) {
  return http.post(`${BASE}/api/v1/accounts/${accountId}/trades/${side}`,
    JSON.stringify({
      idempotencyKey: crypto.randomUUID(),
      targetAssetCode: 'BTC',
      targetAssetType: 'CRYPTO',
      paymentCurrency: 'KRW',
      quantity,
      unitPrice: BTC_PRICE_KRW,
    }),
    { headers: ownerHeaders(accountId), tags: { name: side } });
}

export function buy(accountId, quantity) {
  return trade('buy', accountId, quantity);
}

export function sell(accountId, quantity) {
  return trade('sell', accountId, quantity);
}

export function getPortfolio(accountId, tags) {
  return http.get(`${BASE}/api/v1/portfolios/${accountId}`, { headers: ownerHeaders(accountId), tags });
}

export function ok(response, label) {
  return check(response, { [`${label} 200`]: (r) => r.status === 200 });
}
