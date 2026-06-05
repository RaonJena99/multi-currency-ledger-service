package com.github.raonjena99.multi_currency_ledger_service.repository;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.Transaction;
import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.TransactionEntry;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
public class TransactionRepositoryTest extends IntegrationTestSupport {

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("DB 저장 및 조회 시 DECIMAL(36, 18)의 소수점 정밀도가 손실 없이 유지된다")
    void decimal_precision_is_preserved_in_db() {
        // given
        UUID txId = UUID.randomUUID();
        Transaction tx = new Transaction(txId, "TRADE", "Precision Test");
        UUID accountId = UUID.randomUUID();

        // 테스트용 더미 계좌를 DB에 직접 먼저 삽입
        em.createNativeQuery("INSERT INTO accounts (id, owner_name) VALUES (:id, 'Test User')")
            .setParameter("id", accountId)
            .executeUpdate();

        // 18자리 소수점을 가진 초정밀 수량
        BigDecimal preciseQuantity = new BigDecimal("0.123456789012345678");
        BigDecimal price = new BigDecimal("50000");

        tx.addBuyEntry(accountId, "ETH", AssetType.CRYPTO, preciseQuantity, price, BigDecimal.ONE);
        
        // when
        em.persist(tx);
        em.flush(); // DB에 Insert 쿼리 강제 전송
        em.clear(); // 1차 캐시 비우기

        // then
        Transaction savedTx = em.find(Transaction.class, txId);
        TransactionEntry savedEntry = savedTx.getEntries().get(0);
        BigDecimal savedQuantity = savedEntry.getQuantity();

        // BigDecimal의 scale과 값이 완벽히 일치하는지 비교
        assertThat(savedQuantity).isEqualByComparingTo("0.123456789012345678");
    }
}
