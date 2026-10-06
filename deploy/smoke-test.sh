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

GRAFANA_PASSWORD="smoke-test-grafana"
# 모니터링 포트는 127.0.0.1 에만 열린다. 로컬에서 돌고 있는 개발용 스택(9090/3000)과 겹치지 않게 옮긴다.
export APP_IMAGE="$IMAGE" APP_PORT="$PORT" DB_PASSWORD="smoke-test" GATEWAY_SHARED_SECRET="$SECRET"   GRAFANA_ADMIN_PASSWORD="$GRAFANA_PASSWORD" PROMETHEUS_PORT="${SMOKE_PROMETHEUS_PORT:-19090}"   GRAFANA_PORT="${SMOKE_GRAFANA_PORT:-13000}"
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

echo "▶ 헬스 확인: 관리 포트(9091)는 호스트에 노출되지 않으므로 컨테이너 안에서 확인한다"
# 응답을 변수에 받은 뒤 검사한다. curl | grep -q 로 이으면 grep 이 먼저 끝날 때 curl 이 SIGPIPE 로 실패해
# pipefail 때문에 지표처럼 응답이 큰 경로에서 간헐적으로 실패한다.
health=$(compose exec -T app curl -fsS http://localhost:9091/actuator/health)
grep -q '"status":"UP"' <<< "$health"
metrics=$(compose exec -T app curl -fsS http://localhost:9091/actuator/prometheus)
grep -q 'ledger_integrity_mismatches' <<< "$metrics"

echo "▶ 노출 확인: 앱 포트에서는 관리용 엔드포인트가 응답하지 않아야 한다"
# 응답하면 앱에 닿는 누구나 플랫폼 보유액 같은 사업 지표를 읽을 수 있다.
for path in /actuator/prometheus /actuator/health; do
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT$path")
  if [ "$code" != "404" ]; then
    echo "앱 포트의 $path 가 404 가 아니라 $code 를 반환했습니다." >&2
    exit 1
  fi
done

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

echo "▶ 원장 정합성 점검 실행 확인"
expect "정합성 점검" "$(admin_post /api/v1/admin/ledger/integrity-checks '')" 200

echo "▶ 모니터링 확인: Prometheus 가 앱 지표를 수집하고 알림 규칙을 읽었는지, Grafana 에 대시보드가 있는지"
# 모니터링 서비스에는 curl 이 없으므로 같은 네트워크의 앱 컨테이너에서 호출한다.
in_app() { compose exec -T app curl -fsS "$@"; }

# 수집 주기(15초)를 고려해 최대 90초 기다린다.
scraped=""
for _ in $(seq 1 30); do
  up=$(in_app 'http://prometheus:9090/api/v1/query?query=up%7Bjob%3D%22ledger%22%7D' || true)
  if grep -q '"1"\]' <<< "$up"; then scraped=yes; break; fi
  sleep 3
done
if [ -z "$scraped" ]; then
  echo "Prometheus 가 앱(app:9091)을 수집하지 못했습니다: $up" >&2
  exit 1
fi

rules=$(in_app http://prometheus:9090/api/v1/rules)
for alert in LedgerIntegrityMismatch LedgerDeadLetterUnresolved OutboxDeadLetters LedgerServiceDown; do
  grep -q "\"$alert\"" <<< "$rules" || { echo "알림 규칙 $alert 가 로드되지 않았습니다." >&2; exit 1; }
done

# Grafana 는 기동하면서 프로비저닝 파일을 읽는다.
provisioned=""
for _ in $(seq 1 30); do
  if in_app -u "admin:$GRAFANA_PASSWORD" http://grafana:3000/api/dashboards/uid/ledger-overview >/dev/null 2>&1; then
    provisioned=yes; break
  fi
  sleep 3
done
if [ -z "$provisioned" ]; then
  echo "Grafana 에 ledger-overview 대시보드가 프로비저닝되지 않았습니다." >&2
  exit 1
fi
in_app -u "admin:$GRAFANA_PASSWORD" http://grafana:3000/api/datasources/uid/prometheus >/dev/null

echo "✔ 스모크 테스트 통과"
