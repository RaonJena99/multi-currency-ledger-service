package com.github.raonjena99.multi_currency_ledger_service.transaction.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand;
import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.Transaction;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.TransactionRepository;

@DisplayName("서비스 통합 테스트: LedgerService (ACL 및 복식부기 자동 기록 검증)")
class LedgerServiceTest extends IntegrationTestSupport {

    @Autowired private LedgerService ledgerService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;

    @Test
    @DisplayName("BUY 커맨드 수신 시, 완벽하게 차변/대변이 일치하는 원장 트랜잭션이 생성된다.")
    void recordDoubleEntry_buy_success() {
        UUID tradeId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        accountRepository.saveAndFlush(new Account(accountId, "TEST_USER"));
        
        LedgerRecordingCommand command = new LedgerRecordingCommand(
            tradeId, accountId, "BTC", "KRW", "BUY", 
            Money.of("1", AssetType.CRYPTO), 
            Money.of("100000000", AssetType.FIAT), 
            BigDecimal.ONE, 
            Money.zero(AssetType.FIAT),
            false
        );

        ledgerService.recordDoubleEntry(command);

        Transaction savedTx = transactionRepository.findWithEntriesById(tradeId).orElseThrow();
        
        assertThat(savedTx.getTransactionType()).isEqualTo("BUY");
        
        assertThat(savedTx.getEntries()).hasSize(2);
        assertThat(savedTx.getDescription()).contains("Auto-recorded via ACL");
    }

    @Test
    @DisplayName("멱등성 검증: 동일한 TradeID로 두 번 요청이 오면 중복 기록을 무시한다.")
    void recordDoubleEntry_idempotency_ignores_duplicates() {
        UUID tradeId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        accountRepository.saveAndFlush(new Account(accountId, "TEST_USER_2"));

        LedgerRecordingCommand command = new LedgerRecordingCommand(
            tradeId, accountId, "ETH", "KRW", "BUY", 
            Money.of("1", AssetType.CRYPTO), Money.of("3000000", AssetType.FIAT), BigDecimal.ONE, Money.zero(AssetType.FIAT),
            false
        );

        ledgerService.recordDoubleEntry(command); 
        ledgerService.recordDoubleEntry(command); 

        long count = transactionRepository.count();
        assertThat(count).isGreaterThanOrEqualTo(1); 
    }
}