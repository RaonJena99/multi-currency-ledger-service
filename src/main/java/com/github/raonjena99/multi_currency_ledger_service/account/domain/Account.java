package com.github.raonjena99.multi_currency_ledger_service.account.domain;

import java.util.Objects;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account extends BaseEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "owner_name", nullable = false, length = 100)
    private String ownerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    private Account(UUID id, String ownerName) {
        Objects.requireNonNull(id, "Account ID cannot be null");
        if (ownerName == null || ownerName.isBlank()) {
            throw new IllegalArgumentException("Owner name cannot be null or blank");
        }
        
        this.id = id;
        this.ownerName = ownerName;
        this.status = AccountStatus.ACTIVE;
    }

    // 개설
    public static Account open(UUID id, String ownerName) {
        return new Account(id, ownerName);
    }

    // 도메인 행위(Behavior) 메서드 내재화
    public boolean isActive() {
        return this.status == AccountStatus.ACTIVE;
    }

    /**
     * 부정 거래 탐지(FDS) 등에 의해 계좌를 일시 정지
     */
    public void suspend() {
        if (this.status == AccountStatus.CLOSED) {
            throw new IllegalStateException("Cannot suspend an already closed account");
        }
        this.status = AccountStatus.SUSPENDED;
    }

    /**
     * 계좌를 정상 상태로 복구
     */
    public void activate() {
        if (this.status == AccountStatus.CLOSED) {
            throw new IllegalStateException("Cannot reactivate a closed account");
        }
        this.status = AccountStatus.ACTIVE;
    }

    /**
     * 계좌를 영구 폐쇄
     */
    public void close() {
        this.status = AccountStatus.CLOSED;
    }

    @Override
    public boolean isNew() {
        return this.getCreatedAt() == null;
    }
}
