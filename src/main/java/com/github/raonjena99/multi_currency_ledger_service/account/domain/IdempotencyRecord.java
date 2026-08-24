package com.github.raonjena99.multi_currency_ledger_service.account.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "idempotency_records", indexes = {
    @Index(name = "idx_idempotency_created_at", columnList = "createdAt")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord implements Persistable<String> {
    @Id
    private String idempotencyKey;

    private OffsetDateTime createdAt;

    /**
     * 이 키로 완료된 거래의 ID. 거래가 커밋될 때 함께 기록됩니다.
     *
     * <p>이 값이 없으면 타임아웃 후 재시도하는 클라이언트가 자신의 성공한 거래 ID 를 되찾을
     * 방법이 없어, 멱등 재전송이 409 로만 끝납니다. 값이 비어 있는 레코드는 "처리 중"을 의미합니다.
     */
    @Column(name = "trade_id")
    private UUID tradeId;

    public IdempotencyRecord(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
        this.createdAt = OffsetDateTime.now();
    }

    /**
     * 거래가 성공적으로 완료되었음을 기록합니다. 같은 트랜잭션에서 호출되어야
     * 거래 커밋과 원자적으로 반영됩니다.
     */
    public void complete(UUID completedTradeId) {
        this.tradeId = completedTradeId;
    }

    @Override
    public String getId() {
        return idempotencyKey;
    }

    @Override
    public boolean isNew() {
        // 멱등성 레코드는 생성(Insert)만 존재하며 수정(Update)되지 않습니다.
        // 항상 true를 반환하여 JPA가 무의미한 Select 쿼리를 날리지 않고 곧바로 Insert 하도록 강제합니다.
        return true;
    }
}
