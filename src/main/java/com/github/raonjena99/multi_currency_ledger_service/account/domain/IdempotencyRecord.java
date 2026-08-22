package com.github.raonjena99.multi_currency_ledger_service.account.domain;

import java.time.OffsetDateTime;

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
public class IdempotencyRecord {
    @Id
    private String idempotencyKey;

    private OffsetDateTime createdAt;

    public IdempotencyRecord(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
        this.createdAt = OffsetDateTime.now();
    }
}
