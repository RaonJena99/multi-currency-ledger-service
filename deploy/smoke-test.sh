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
    echo "::group::app logs"
    compose logs --no-color --tail 200 app || true
    echo "::endgroup::"
  fi
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT

echo "▶ 스택 기동: $IMAGE"
# --wait: 헬스체크가 있는 서비스(app, postgres, redis)가 healthy 가 될 때까지 기다린다.
compose up -d --no-build --pull never --wait --wait-timeout 240

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

echo "✔ 스모크 테스트 통과"
