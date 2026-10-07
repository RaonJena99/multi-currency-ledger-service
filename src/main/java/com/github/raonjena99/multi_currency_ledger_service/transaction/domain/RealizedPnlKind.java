package com.github.raonjena99.multi_currency_ledger_service.transaction.domain;

import java.util.Arrays;
import java.util.List;

import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

/**
 * 실현 손익 분개의 종류입니다.
 *
 * <p>실현 손익은 <b>고객의 손익</b>이라 회사 시스템 계정이 아니라 고객 본인 계정의 분개로 기록합니다. 분개의
 * 자산 코드({@link #assetCode()})가 종류를 나타내고, 이익은 대변, 손실은 차변입니다.
 *
 * <p>이 분개는 잔고 행이 없고(고객이 쓸 수 있는 자산이 아니다) 거래의 원 금액도 아닙니다. 그래서 잔고와 분개
 * 누계를 비교하는 정합성 점검과 PG 정산과 맞춰 보는 대사 후보 조회에서는 {@link #assetCodes()} 로 걸러냅니다.
 */
public enum RealizedPnlKind {

    /** 외화(법정화폐)가 계좌에서 나가며 생긴 환차손익. 외화 출금, 외화로 결제한 매수. */
    FX("REALIZED_PNL_FX"),

    /** 자산을 팔아 생긴 매매 손익. */
    TRADING("REALIZED_PNL_TRADING");

    private static final List<String> ASSET_CODES = Arrays.stream(values()).map(RealizedPnlKind::assetCode).toList();

    private final String assetCode;

    RealizedPnlKind(String assetCode) {
        this.assetCode = assetCode;
    }

    /** 손익 분개의 자산 코드. {@code transaction_entries.asset_code}(20자)에 들어간다. */
    public String assetCode() {
        return assetCode;
    }

    /**
     * 계좌에서 나가는 쪽 자산의 유형으로 손익의 종류를 정합니다. 법정화폐가 나가면 환차손익, 그 밖의 자산이
     * 나가면 매매 손익입니다.
     */
    public static RealizedPnlKind of(AssetType outgoingAssetType) {
        return outgoingAssetType == AssetType.FIAT ? FX : TRADING;
    }

    public static boolean isRealizedPnl(String assetCode) {
        return ASSET_CODES.contains(assetCode);
    }

    /** SQL 에서 손익 분개를 거르거나 모을 때 쓰는 자산 코드 목록. */
    public static List<String> assetCodes() {
        return ASSET_CODES;
    }
}
