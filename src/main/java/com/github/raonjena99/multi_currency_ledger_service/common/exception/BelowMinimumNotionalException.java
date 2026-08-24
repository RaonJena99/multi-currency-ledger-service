package com.github.raonjena99.multi_currency_ledger_service.common.exception;

/**
 * 거래 대금이 결제 통화의 최소 단위보다 작아, 통화 스케일로 정규화하면
 * 금액이 사라지거나 부풀려지는 거래일 때 발생합니다.
 *
 * 이 검증이 없으면 0.4 KRW 짜리 자산을 0 원에 취득하거나(무상 취득),
 * 0.6 KRW 어치를 팔아 1 원을 받는(통화 증식) 경로가 열립니다.
 */
public class BelowMinimumNotionalException extends RuntimeException {
    public BelowMinimumNotionalException(String message) { super(message); }
}
