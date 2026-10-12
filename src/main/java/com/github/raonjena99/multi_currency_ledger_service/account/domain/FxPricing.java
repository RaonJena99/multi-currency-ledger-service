package com.github.raonjena99.multi_currency_ledger_service.account.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 환전 가격 산정입니다. 시세(매매기준율)에 스프레드를 붙여 고객 환율을 정하고, 낸 금액으로 받을 금액을 계산합니다.
 *
 * <pre>
 *   실효 스프레드 = 스프레드 × (1 − 우대율)
 *   고객 환율     = 시세 × (1 ± 실효 스프레드)     외화를 살 때 +, 팔 때 −
 * </pre>
 *
 * <p>시세는 <b>1 외화 = 기준 통화 얼마</b> 입니다. 예: 시세 1,300, 스프레드 1.75%, 우대 90% 이면 실효 스프레드는
 * 0.175% 이고 외화를 살 때 고객 환율은 1,302.275, 팔 때는 1,297.725 입니다.
 *
 * <p><b>반올림은 모두 회사에 유리한 방향입니다.</b> 반올림이 통화를 만들어 내면 안 되기 때문입니다.
 * 고객 환율은 소수 18자리에서 살 때 올림, 팔 때 내림하고, 받을 금액은 받는 통화의 최소 단위로 내림합니다
 * (기존 매수·매도에서 고객이 수취하는 금액을 내림하는 것과 같은 규칙).
 *
 * <p>이 규칙 덕분에 어떤 입력에서도 고객이 받는 금액의 시세 가치는 낸 금액을 넘지 않습니다. 그 차이가 회사의
 * 스프레드 수익입니다.
 */
public final class FxPricing {

    /** 고객 환율의 소수 자릿수. {@code numeric(36,18)} 컬럼과 같다. */
    public static final int RATE_SCALE = 18;

    private FxPricing() {
    }

    /**
     * 가격 산정 결과.
     *
     * @param effectiveSpread 실제로 적용된 스프레드 (스프레드 × (1 − 우대율))
     * @param customerRate    고객에게 적용되는 환율 (1 외화 = 기준 통화)
     * @param receivedAmount  고객이 받을 금액. 받는 통화의 최소 단위로 내림
     */
    public record Priced(BigDecimal effectiveSpread, BigDecimal customerRate, BigDecimal receivedAmount) {
    }

    /**
     * 낸 금액에 대해 고객 환율과 받을 금액을 계산합니다.
     *
     * @param direction       환전 방향
     * @param paidAmount      고객이 내는 금액 (사면 기준 통화, 팔면 외화)
     * @param marketRate      시세. 1 외화 = 기준 통화 얼마
     * @param spread          통화별로 설정한 스프레드
     * @param preferentialRate 계좌의 우대율 (0 이상 1 이하)
     * @param receivedScale   받는 통화의 소수 자릿수
     * @throws IllegalArgumentException 입력이 유효 범위를 벗어난 경우
     */
    public static Priced price(FxDirection direction, BigDecimal paidAmount, BigDecimal marketRate,
                               BigDecimal spread, BigDecimal preferentialRate, int receivedScale) {
        requirePositive(paidAmount, "낸 금액");
        BigDecimal effectiveSpread = effectiveSpread(spread, preferentialRate);
        BigDecimal customerRate = customerRate(direction, marketRate, effectiveSpread);
        BigDecimal receivedAmount = receivedAmount(direction, paidAmount, customerRate, receivedScale);
        return new Priced(effectiveSpread, customerRate, receivedAmount);
    }

    /**
     * 우대율을 반영한 실효 스프레드를 구합니다.
     *
     * @param spread           스프레드. 0 이상 1 미만 (1 이상이면 파는 쪽 환율이 0 이하가 된다)
     * @param preferentialRate 우대율. 0 이상 1 이하
     */
    public static BigDecimal effectiveSpread(BigDecimal spread, BigDecimal preferentialRate) {
        if (spread == null || spread.signum() < 0 || spread.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("스프레드는 0 이상 1 미만이어야 합니다: " + spread);
        }
        if (preferentialRate == null || preferentialRate.signum() < 0
                || preferentialRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("우대율은 0 이상 1 이하이어야 합니다: " + preferentialRate);
        }
        return spread.multiply(BigDecimal.ONE.subtract(preferentialRate));
    }

    /**
     * 고객 환율을 구합니다. 외화를 살 때는 올림, 팔 때는 내림해 항상 시세에서 벌어지는 쪽으로 맺습니다.
     */
    public static BigDecimal customerRate(FxDirection direction, BigDecimal marketRate, BigDecimal effectiveSpread) {
        requirePositive(marketRate, "시세");
        if (effectiveSpread == null || effectiveSpread.signum() < 0
                || effectiveSpread.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("실효 스프레드는 0 이상 1 미만이어야 합니다: " + effectiveSpread);
        }
        return switch (direction) {
            case BUY_FOREIGN -> marketRate.multiply(BigDecimal.ONE.add(effectiveSpread))
                    .setScale(RATE_SCALE, RoundingMode.UP);
            case SELL_FOREIGN -> marketRate.multiply(BigDecimal.ONE.subtract(effectiveSpread))
                    .setScale(RATE_SCALE, RoundingMode.DOWN);
        };
    }

    /**
     * 받을 금액을 구합니다. 받는 통화의 최소 단위로 내림합니다.
     *
     * @param paidAmount    낸 금액 (사면 기준 통화, 팔면 외화)
     * @param customerRate  고객 환율 (1 외화 = 기준 통화)
     * @param receivedScale 받는 통화의 소수 자릿수
     */
    public static BigDecimal receivedAmount(FxDirection direction, BigDecimal paidAmount, BigDecimal customerRate,
                                            int receivedScale) {
        requirePositive(paidAmount, "낸 금액");
        requirePositive(customerRate, "고객 환율");
        return switch (direction) {
            // 기준 통화를 내고 외화를 받는다: 낸 금액 ÷ 환율
            case BUY_FOREIGN -> paidAmount.divide(customerRate, receivedScale, RoundingMode.DOWN);
            // 외화를 내고 기준 통화를 받는다: 낸 금액 × 환율
            case SELL_FOREIGN -> paidAmount.multiply(customerRate).setScale(receivedScale, RoundingMode.DOWN);
        };
    }

    private static void requirePositive(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(label + "은(는) 0 보다 커야 합니다: " + value);
        }
    }
}
