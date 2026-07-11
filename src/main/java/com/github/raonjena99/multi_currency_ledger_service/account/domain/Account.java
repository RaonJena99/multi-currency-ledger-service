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

/**
 * Account(계좌) 엔티티 클래스.
 * 사용자의 자산을 관리하는 핵심 도메인 객체로, 계좌의 기본 정보와 상태(AccountStatus)를 관리합니다.
 */
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

    /**
     * 새로운 Account(계좌)를 개설합니다.
     *
     * @param id 계좌의 고유 식별자 (UUID)
     * @param ownerName 계좌 소유주 이름
     * @return 생성된 Account(계좌) 엔티티 객체
     * @throws IllegalArgumentException ownerName이 null이거나 비어있는 경우
     * @throws NullPointerException id가 null인 경우
     */
    public static Account open(UUID id, String ownerName) {
        return new Account(id, ownerName);
    }

    /**
     * 현재 Account(계좌)가 정상 거래 가능한 상태인지 확인합니다.
     *
     * @return ACTIVE 상태이면 true, 그렇지 않으면 false
     */
    public boolean isActive() {
        return this.status == AccountStatus.ACTIVE;
    }

    /**
     * 부정 거래 탐지(FDS) 등에 의해 Account(계좌)를 일시 정지(SUSPENDED) 상태로 변경합니다.
     *
     * @throws IllegalStateException 이미 해지된(CLOSED) 계좌일 경우
     */
    public void suspend() {
        // 이미 해지된 계좌는 상태를 변경할 수 없으므로 예외 발생
        if (this.status == AccountStatus.CLOSED) {
            throw new IllegalStateException("Cannot suspend an already closed account");
        }
        this.status = AccountStatus.SUSPENDED;
    }

    /**
     * 일시 정지된 Account(계좌)를 정상(ACTIVE) 상태로 복구합니다.
     *
     * @throws IllegalStateException 이미 해지된(CLOSED) 계좌일 경우
     */
    public void activate() {
        // 이미 해지된 계좌는 복구할 수 없으므로 예외 발생
        if (this.status == AccountStatus.CLOSED) {
            throw new IllegalStateException("Cannot reactivate a closed account");
        }
        this.status = AccountStatus.ACTIVE;
    }

    /**
     * Account(계좌)를 영구 폐쇄(CLOSED) 상태로 변경합니다.
     */
    public void close() {
        this.status = AccountStatus.CLOSED;
    }

    /**
     * 해당 엔티티가 새로 생성된 객체인지 여부를 반환합니다.
     * Spring Data JPA의 Persistable 인터페이스 구현체로, createdAt이 null인지 확인하여 신규 객체 여부를 판단합니다.
     *
     * @return 신규 객체일 경우 true, 영속화된 객체일 경우 false
     */
    @Override
    public boolean isNew() {
        return this.getCreatedAt() == null;
    }
}
