package com.github.raonjena99.multi_currency_ledger_service.common.exception;

/**
 * 시세를 조회할 공급자가 없는 자산 코드로 거래·조회를 시도했을 때 발생합니다.
 *
 * <p>ISO 4217 통화도 아니고 설정된 암호화폐 심볼도 아닌 코드(예: {@code AAPL})가 여기에 해당합니다.
 * 무료 시세 공급자는 주식을 다루지 않으므로, 도메인 모델은 {@code AssetType.STOCK} 을 표현할 수
 * 있어도 시세 조회 단계에서 막힙니다.
 *
 * <p><b>재시도로 해결되지 않는 영구 오류</b>라는 점이 중요합니다. 그래서 두 곳에서 특별 취급합니다.
 * <ul>
 *   <li>서킷 브레이커의 {@code ignoreExceptions} — 존재하지 않는 자산 요청 몇 건이 정상 환율
 *       조회까지 차단하는 것을 막습니다.</li>
 *   <li>어댑터 폴백 — 캐시로도 해결되지 않으므로 폴백이 삼키지 않고 그대로 올립니다.
 *       삼키면 클라이언트가 일시적 장애로 오인해 무한 재시도합니다.</li>
 * </ul>
 */
public class UnsupportedAssetCodeException extends RuntimeException {
    public UnsupportedAssetCodeException(String message) { super(message); }
}
