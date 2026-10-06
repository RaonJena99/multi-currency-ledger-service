#!/usr/bin/env bash
# 빌드한 이미지를 운영용 스택(deploy/docker-compose.yml)으로 실제로 띄워 기동과 기본 API 동작을 확인한다.
#
#   deploy/smoke-test.sh <image>      예) deploy/smoke-test.sh ledger:ci
#
# 단위·통합 테스트는 로컬 빌드 산출물로 돌기 때문에 "이미지로 만든 jar 가 뜨는가"는 검증하지 못한다.
# (예: Dockerfile 이 lombok.config 를 빠뜨리면 테스트는 전부 통과하지만 이미지는 기동하지 못한다.)
set -euo pipefail

IMAGE="${1:?사용법: $0 <image>}"
PROJECT="ledger-smoke-$$"
PORT="${SMOKE_PORT:-18080}"
SECRET="smoke-test-secret"
cd "$(dirname "$0")"

export APP_IMAGE="$IMAGE" APP_PORT="$PORT" DB_PASSWORD="smoke-test" GATEWAY_SHARED_SECRET="$SECRET"
compose() { docker compose -p "$PROJECT" -f docker-compose.yml "$@"; }

cleanup() {
  local status=$?
  if [ "$status" -ne 0 ]; then
    echo "::group::services"
    compose ps -a || true
    echo "::endgroup::"
    echo "::group::logs"
    compose logs --no-color --tail 200 || true
    echo "::endgroup::"
  fi
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT

echo "▶ 스택 기동: $IMAGE"
# 이미지 가져오기는 기본 정책(없는 것만 받기)을 따른다. 검사할 앱 이미지는 이미 로컬에 빌드되어 있으므로
# 받지 않고, 인프라 이미지(postgres/redis/kafka)만 받는다. --pull never 는 모든 서비스에 적용되어
# 깨끗한 CI 러너에서는 인프라 이미지를 받지 못해 즉시 실패한다.
# --wait: 헬스체크가 있는 서비스(app, postgres, redis)가 healthy 가 될 때까지 기다린다.
compose up -d --no-build --wait --wait-timeout 240

echo "▶ 헬스 확인"
curl -fsS "http://localhost:$PORT/actuator/health" | grep -q '"status":"UP"'

echo "▶ API 확인: 존재하지 않는 계좌의 포트폴리오 조회는 404 여야 한다"
# 인증 필터 → 보안 설정 → 컨트롤러 → JPA 조회 → 예외 매핑까지 한 번에 지나는 경로다.
code=$(curl -s -o /dev/null -w '%{http_code}' \
  -H "X-Gateway-Secret: $SECRET" -H "X-Auth-Subject: smoke" -H "X-Auth-Roles: ADMIN" \
  "http://localhost:$PORT/api/v1/portfolios/00000000-0000-0000-0000-00000000abcd")
if [ "$code" != "404" ]; then
  echo "기대한 404 가 아니라 $code 가 반환되었습니다." >&2
  exit 1
fi

echo "▶ 인증 확인: 게이트웨이 시크릿이 없으면 거부되어야 한다"
code=$(curl -s -o /dev/null -w '%{http_code}' \
  -H "X-Auth-Subject: smoke" -H "X-Auth-Roles: ADMIN" \
  "http://localhost:$PORT/api/v1/portfolios/00000000-0000-0000-0000-00000000abcd")
case "$code" in
  401|403) ;;
  *) echo "시크릿 없는 요청이 $code 로 통과했습니다." >&2; exit 1 ;;
esac

echo "▶ 거래 흐름 확인: 계좌 개설 → 입금 → 출금 → 잔고 초과 출금 거부 → 포트폴리오 조회"
# 원화 계좌의 원화 입출금은 외부 시세 API 를 부르지 않으므로(같은 통화 환율은 1) 외부망 없이도 결정적이다.
# 매수·매도는 실제 시세 공급자를 호출해야 해서 여기서는 다루지 않는다(통합 테스트 CashFlowE2ETest 가 검증).
ACCOUNT="00000000-0000-0000-0000-0000000005e1"
admin_post() {
  curl -s -o /dev/null -w '%{http_code}' -X POST \
    -H "X-Gateway-Secret: $SECRET" -H "X-Auth-Subject: smoke-admin" -H "X-Auth-Roles: ADMIN" \
    -H "Content-Type: application/json" -d "$2" "http://localhost:$PORT$1"
}
expect() {
  if [ "$2" != "$3" ]; then
    echo "$1: 기대한 $3 가 아니라 $2 가 반환되었습니다." >&2
    exit 1
  fi
}
expect "계좌 개설" "$(admin_post /api/v1/admin/accounts \
  "{\"accountId\":\"$ACCOUNT\",\"ownerName\":\"SMOKE\",\"baseCurrency\":\"KRW\"}")" 201
expect "입금" "$(admin_post /api/v1/admin/accounts/$ACCOUNT/deposits \
  '{"idempotencyKey":"smoke-dep-1","currency":"KRW","amount":10000}')" 200
expect "출금" "$(admin_post /api/v1/admin/accounts/$ACCOUNT/withdrawals \
  '{"idempotencyKey":"smoke-wd-1","currency":"KRW","amount":3000}')" 200
expect "잔고 초과 출금" "$(admin_post /api/v1/admin/accounts/$ACCOUNT/withdrawals \
  '{"idempotencyKey":"smoke-wd-2","currency":"KRW","amount":100000}')" 409

portfolio=$(curl -fsS -H "X-Gateway-Secret: $SECRET" -H "X-Auth-Subject: smoke-user" \
  -H "X-Auth-Account-Id: $ACCOUNT" "http://localhost:$PORT/api/v1/portfolios/$ACCOUNT")
if ! echo "$portfolio" | grep -Eq '"assetCode":"KRW","quantity":7000(\.0+)?[,}]'; then
  echo "포트폴리오의 KRW 잔고가 7000 이 아닙니다: $portfolio" >&2
  exit 1
fi

echo "✔ 스모크 테스트 통과"
