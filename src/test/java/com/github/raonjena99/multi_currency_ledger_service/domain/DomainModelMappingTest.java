package com.github.raonjena99.multi_currency_ledger_service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxEvent;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class DomainModelMappingTest extends IntegrationTestSupport {

    @Autowired
    private EntityManager em;

    @Autowired
    private AccountRepository accountRepository;

    @Test
    @DisplayName("DB 스키마 마이그레이션 및 도메인 모델 매핑 검증")
    void testDomainModelMappings() {
        // 1. Account (Persistable 검증)
        Account account = Account.open(UUID.randomUUID(), "테스트 유저", "KRW");
        accountRepository.saveAndFlush(account);
        assertThat(account.getCreatedAt()).isNotNull();

        // 2. OutboxEvent (Sequence 검증 및 BaseEntity 상속 확인)
        OutboxEvent outboxEvent = new OutboxEvent("Account", account.getId().toString(), "CREATED", "{}");
        em.persist(outboxEvent);
        em.flush();
        assertThat(outboxEvent.getId()).isNotNull();
        assertThat(outboxEvent.getCreatedAt()).isNotNull();

        // 3. SEQUENCE 생성 및 동작 확인 (Native Query)
        Object nextVal = em.createNativeQuery("SELECT nextval('transaction_entry_seq')").getSingleResult();
        assertThat(nextVal).isNotNull();

        // 4. ExternalSettlement 타입 매핑 검증
        ExternalSettlement settlement = ExternalSettlement.create(
            "REF-1234", "INST-A", OffsetDateTime.now(), "Description", 
            Money.zero(AssetType.FIAT, "KRW")
        );
        em.persist(settlement);
        em.flush();
        assertThat(settlement.getId()).isNotNull(); // Native UUID
    }
}
