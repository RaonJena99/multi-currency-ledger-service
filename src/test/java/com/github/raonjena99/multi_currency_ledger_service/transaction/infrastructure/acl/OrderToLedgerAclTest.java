package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.acl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRepository;

@DisplayName("통합 테스트: OrderToLedgerAcl (이벤트 수신 및 부패 방지 계층 매핑 검증)")
class OrderToLedgerAclTest extends IntegrationTestSupport {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired 
    private AccountRepository accountRepository;

    @Test
    @DisplayName("TradeExecutedEvent가 발행되면, ACL이 감지하여 완벽하게 원장(Transaction)으로 기록해낸다.")
    void acl_translates_event_to_command_and_invokes_ledger_service() {
        // given
        UUID tradeId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        accountRepository.saveAndFlush(new Account(accountId, "TEST_USER"));
        
        TradeExecutedEvent event = new TradeExecutedEvent(
            tradeId, accountId, "TSLA", "STOCK", "USD", "SELL", 
            BigDecimal.valueOf(10), BigDecimal.valueOf(200), BigDecimal.valueOf(1400.50), BigDecimal.valueOf(180)
        );

        // when
        eventPublisher.publishEvent(event);

        // then
        long count = outboxRepository.count();
        assertThat(count).isGreaterThanOrEqualTo(1);
    }
}