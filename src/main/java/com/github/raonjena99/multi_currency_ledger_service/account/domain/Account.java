package com.github.raonjena99.multi_currency_ledger_service.account.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.BaseEntity;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.InvalidAccountStateException;

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

    @Column(name = "base_currency", nullable = false, length = 3)
    private String baseCurrency;

    /**
     * 환전 우대율(0.9 = 스프레드의 90% 를 깎아 줌). 지정하지 않았으면 null 이고, 이때는 설정의 기본 우대율을 쓴다.
     * 0 은 "우대 없음"을 명시한 값이다.
     */
    @Column(name = "fx_preferential_rate", precision = 5, scale = 4)
    private BigDecimal fxPreferentialRate;

    private Account(UUID id, String ownerName, String baseCurrency) {
        Objects.requireNonNull(id, "Account ID cannot be null");
        if (ownerName == null || ownerName.isBlank()) {
            throw new IllegalArgumentException("Owner name cannot be null or blank");
        }
        if (baseCurrency == null || baseCurrency.isBlank()) {
            throw new IllegalArgumentException("Base currency cannot be null or blank");
        }
        
        this.id = id;
        this.ownerName = ownerName;
        this.baseCurrency = baseCurrency.toUpperCase();
        this.status = AccountStatus.ACTIVE;
    }

    /**
     * 새로운 Account(계좌)를 개설합니다.
     *
     * @param id 계좌의 고유 식별자 (UUID)
     * @param ownerName 계좌 소유주 이름
     * @param baseCurrency 사용자의 기준 법정 화폐 (예: "KRW", "USD")
     * @return 생성된 Account(계좌) 엔티티 객체
     * @throws IllegalArgumentException ownerName이나 baseCurrency가 null이거나 비어있는 경우
     * @throws NullPointerException id가 null인 경우
     */
    public static Account open(UUID id, String ownerName, String baseCurrency) {
        return new Account(id, ownerName, baseCurrency);
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
        if (this.status == AccountStatus.CLOSED) {
            throw new InvalidAccountStateException("Cannot suspend an already closed account");
        }
        // 같은 상태로 바꾸는 요청을 받아 주면 의미 없는 이력이 쌓이고, 정지가 실제로 적용됐는지 구분할 수 없다.
        if (this.status == AccountStatus.SUSPENDED) {
            throw new InvalidAccountStateException("Account is already suspended");
        }
        this.status = AccountStatus.SUSPENDED;
    }

    /**
     * 일시 정지된 Account(계좌)를 정상(ACTIVE) 상태로 복구합니다.
     *
     * @throws IllegalStateException 이미 해지된(CLOSED) 계좌일 경우
     */
    public void activate() {
        if (this.status == AccountStatus.CLOSED) {
            throw new InvalidAccountStateException("Cannot reactivate a closed account");
        }
        if (this.status == AccountStatus.ACTIVE) {
            throw new InvalidAccountStateException("Account is already active");
        }
        this.status = AccountStatus.ACTIVE;
    }

    /**
     * Account(계좌)를 영구 폐쇄(CLOSED) 상태로 변경합니다.
     */
    public void close() {
        if (this.status == AccountStatus.CLOSED) {
            throw new InvalidAccountStateException("Account is already closed");
        }
        this.status = AccountStatus.CLOSED;
    }

    /**
     * 환전 우대율을 지정합니다.
     *
     * <p>0 이상 1 미만이고 소수 4자리까지입니다. 100% 우대는 스프레드 수익이 사라지므로 허용하지 않으며,
     * 컬럼({@code numeric(5,4)})보다 정밀한 값은 조용히 반올림하지 않고 거부합니다. 설정이 정한 상한은
     * 이 엔티티가 아니라 호출하는 서비스가 확인합니다.
     *
     * @param rate 우대율
     * @throws IllegalArgumentException 범위를 벗어나거나 소수 4자리를 넘는 경우
     */
    public void changeFxPreferentialRate(BigDecimal rate) {
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("우대율은 0 이상 1 미만이어야 합니다: " + rate);
        }
        if (rate.stripTrailingZeros().scale() > 4) {
            throw new IllegalArgumentException("우대율은 소수 4자리까지만 지정할 수 있습니다: " + rate.toPlainString());
        }
        this.fxPreferentialRate = rate;
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
