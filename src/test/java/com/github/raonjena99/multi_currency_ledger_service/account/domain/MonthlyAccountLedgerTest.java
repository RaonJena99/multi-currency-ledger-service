package com.github.raonjena99.multi_currency_ledger_service.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

@DisplayName("도메인 단위 테스트: MonthlyAccountLedger (월차 원장 및 이월 로직 검증)")
class MonthlyAccountLedgerTest {

    @Test
    @DisplayName("최초로 자산을 매수할 때 정상적으로 월차 원장이 초기화된다.")
    void initialize_new_ledger() {
        UUID accountId = UUID.randomUUID();
        MonthlyAccountLedger ledger = MonthlyAccountLedger.initialize(accountId, "BTC", AssetType.CRYPTO, "2026-05", "KRW");

        assertThat(ledger.getAccountId()).isEqualTo(accountId);
        assertThat(ledger.getAssetCode()).isEqualTo("BTC");
        assertThat(ledger.getLedgerMonth()).isEqualTo("2026-05");
        assertThat(ledger.isCarriedForward()).isFalse();
        assertThat(ledger.getBalance().getAmount()).isEqualByComparingTo("0");
        assertThat(ledger.getBalance().getAssetType()).isEqualTo(AssetType.CRYPTO);
        assertThat(ledger.getBalance().getCurrencyCode()).isEqualTo("BTC");
        assertThat(ledger.getAverageUnitPrice()).isEqualByComparingTo("0");
        assertThat(ledger.getBaseCurrency()).isEqualTo("KRW");
        assertThat(ledger.getId()).isNull();
        assertThat(ledger.getVersion()).isEqualTo(0L);
    }

    @Test
    @DisplayName("이전 달의 장부를 기반으로 당월 장부로 이월(Rollover) 처리 시, 잔액과 평단가가 정확히 복사된다.")
    void carry_forward_logic() {
        // given
        UUID accountId = UUID.randomUUID();
        MonthlyAccountLedger prevLedger = MonthlyAccountLedger.initialize(accountId, "ETH", AssetType.CRYPTO, "2026-04", "KRW");
        prevLedger.addBalance(Money.of("10", AssetType.CRYPTO, "ETH"), new BigDecimal("3000000"));

        // when
        MonthlyAccountLedger newLedger = MonthlyAccountLedger.carryForwardFrom(prevLedger, "2026-05");

        // then
        assertThat(newLedger.getLedgerMonth()).isEqualTo("2026-05");
        assertThat(newLedger.isCarriedForward()).isTrue(); // 이월 플래그 활성화 검증
        assertThat(newLedger.getBalance().getAmount()).isEqualByComparingTo("10"); // 잔액 유지 검증
        assertThat(newLedger.getAverageUnitPrice()).isEqualByComparingTo("3000000"); // 평단가 유지 검증
    }

    @Test
    @DisplayName("addBalance - 잘못된 파라미터 예외 발생")
    void addBalance_exceptions() {
        MonthlyAccountLedger ledger = MonthlyAccountLedger.initialize(UUID.randomUUID(), "BTC", AssetType.CRYPTO, "2026-05", "KRW");
        
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ledger.addBalance(null, new BigDecimal("10")))
            .isInstanceOf(IllegalArgumentException.class);
            
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ledger.addBalance(Money.of("-1", AssetType.CRYPTO, "BTC"), new BigDecimal("10")))
            .isInstanceOf(IllegalArgumentException.class);
            
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ledger.addBalance(Money.of("0", AssetType.CRYPTO, "BTC"), new BigDecimal("10")))
            .isInstanceOf(IllegalArgumentException.class);
            
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ledger.addBalance(Money.of("1", AssetType.CRYPTO, "BTC"), null))
            .isInstanceOf(IllegalArgumentException.class);
            
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ledger.addBalance(Money.of("1", AssetType.CRYPTO, "BTC"), new BigDecimal("-1")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("subtractBalance - 잘못된 파라미터 및 잔액 부족 예외 발생")
    void subtractBalance_exceptions() {
        MonthlyAccountLedger ledger = MonthlyAccountLedger.initialize(UUID.randomUUID(), "BTC", AssetType.CRYPTO, "2026-05", "KRW");
        ledger.addBalance(Money.of("10", AssetType.CRYPTO, "BTC"), new BigDecimal("100"));
        
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ledger.subtractBalance(Money.of("-1", AssetType.CRYPTO, "BTC")))
            .isInstanceOf(IllegalArgumentException.class);
            
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ledger.subtractBalance(Money.of("0", AssetType.CRYPTO, "BTC")))
            .isInstanceOf(IllegalArgumentException.class);
            
        // 잔고 부족은 입력 형식 오류가 아니라 계좌 상태와의 충돌이므로 전용 예외로 구분된다.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ledger.subtractBalance(Money.of("20", AssetType.CRYPTO, "BTC")))
            .isInstanceOf(com.github.raonjena99.multi_currency_ledger_service.common.exception.InsufficientBalanceException.class)
            .hasMessageContaining("Insufficient balance");
    }

    @Test
    @DisplayName("subtractBalance - 전량 매도 시 평균 단가 초기화 검증")
    void subtractBalance_fullSell() {
        MonthlyAccountLedger ledger = MonthlyAccountLedger.initialize(UUID.randomUUID(), "BTC", AssetType.CRYPTO, "2026-05", "KRW");
        ledger.addBalance(Money.of("10", AssetType.CRYPTO, "BTC"), new BigDecimal("100"));
        
        BigDecimal lastAvg = ledger.subtractBalance(Money.of("10", AssetType.CRYPTO, "BTC"));
        
        assertThat(lastAvg).isEqualByComparingTo("100");
        assertThat(ledger.getBalance().isZero()).isTrue();
        assertThat(ledger.getAverageUnitPrice().compareTo(BigDecimal.ZERO) == 0).isTrue();
    }
}
