package com.github.raonjena99.multi_currency_ledger_service.account.domain;

import java.time.OffsetDateTime;

import org.springframework.data.domain.Persistable;

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

    public IdempotencyRecord(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
        this.createdAt = OffsetDateTime.now();
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
