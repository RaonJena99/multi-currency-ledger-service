package com.github.raonjena99.multi_currency_ledger_service.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.Transaction;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.TransactionRepository;

@Transactional
@DisplayName("영속성 통합 테스트: TransactionRepository (@PrePersist 검증)")
class TransactionRepositoryTest extends IntegrationTestSupport {

    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;

    @Test
    @DisplayName("DB 저장 직전 @PrePersist가 발동하여 대차 불일치 데이터를 원천 차단한다.")
    void persist_fails_due_to_pre_persist_validation() {
        UUID accountId = UUID.randomUUID();
        // FK 방어
        accountRepository.saveAndFlush(Account.open(accountId, "TEST_USER"));

        Transaction transaction = Transaction.record(UUID.randomUUID(), "BUY", "Hack");
        
        // 차변: 2 * 50,000 = 100,000
        transaction.addBuyEntry(accountId, "BTC", Money.of("2", AssetType.CRYPTO, "KRW"), Money.of("50000", AssetType.FIAT, "KRW"), BigDecimal.ONE, "KRW");
        // 대변: 1 * 50,000 = 50,000
        transaction.addSellEntry(accountId, "KRW", Money.of("1", AssetType.FIAT, "KRW"), Money.of("50000", AssetType.FIAT, "KRW"), BigDecimal.ONE, Money.zero(AssetType.FIAT, "KRW"), "KRW");

        // 예외 검증
        assertThatThrownBy(() -> transactionRepository.saveAndFlush(transaction))
                            .isInstanceOf(Exception.class)
                            .hasRootCauseInstanceOf(IllegalStateException.class);
    }
}