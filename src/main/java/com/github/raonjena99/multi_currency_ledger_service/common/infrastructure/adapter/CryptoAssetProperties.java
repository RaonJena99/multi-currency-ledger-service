package com.github.raonjena99.multi_currency_ledger_service.common.infrastructure.adapter;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * 암호화폐 심볼과 CoinGecko coin id 의 매핑입니다.
 *
 * <p>CoinGecko 는 자체 id 네임스페이스({@code bitcoin}, {@code ethereum})를 쓰고 심볼로는
 * 조회할 수 없으므로 매핑이 필요합니다. 이 매핑은 <b>라우팅 기준</b>도 겸합니다.
 * 여기에 등록된 심볼이면 암호화폐로 보고 CoinGecko 로 보냅니다.
 *
 * <p>ISO 4217 조회로 법정화폐를 판별하고 나머지를 전부 암호화폐로 넘기는 방식은 쓰지 않습니다.
 * 그러면 {@code AAPL} 같은 코드가 CoinGecko 로 흘러가 "빈 응답"이라는 모호한 실패로 끝납니다.
 * 명시적 허용 목록이라야 지원하지 않는 자산을 지원하지 않는다고 말할 수 있습니다.
 */
@Component
@ConfigurationProperties(prefix = "ledger.external.crypto")
@Getter
@Setter
public class CryptoAssetProperties {

    /** 심볼(대문자) → CoinGecko coin id. */
    private Map<String, String> symbolIds = new LinkedHashMap<>();

    /**
     * 해당 코드가 암호화폐로 등록되어 있는지 확인합니다.
     *
     * @param assetCode 자산 코드
     * @return 등록되어 있으면 true
     */
    public boolean isCrypto(String assetCode) {
        return assetCode != null && symbolIds.containsKey(assetCode.toUpperCase());
    }

    /**
     * 심볼에 대응하는 CoinGecko coin id 를 반환합니다.
     *
     * @param assetCode 자산 코드
     * @return coin id. 등록되지 않은 심볼이면 null
     */
    public String coinIdOf(String assetCode) {
        return assetCode == null ? null : symbolIds.get(assetCode.toUpperCase());
    }
}
