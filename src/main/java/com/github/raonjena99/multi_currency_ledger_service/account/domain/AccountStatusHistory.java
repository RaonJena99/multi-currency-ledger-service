package com.github.raonjena99.multi_currency_ledger_service.account.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계좌 상태 변경(정지·해제·해지) 한 건의 기록입니다. 누가, 언제, 왜 바꿨는지를 남깁니다.
 */
@Entity
@Table(name = "account_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, length = 20)
    private AccountStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private AccountStatus toStatus;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "changed_by", nullable = false, length = 255)
    private String changedBy;

    @Column(name = "changed_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime changedAt;

    public static AccountStatusHistory record(UUID accountId, AccountStatus fromStatus, AccountStatus toStatus,
                                              String reason, String changedBy) {
        AccountStatusHistory history = new AccountStatusHistory();
        history.accountId = accountId;
        history.fromStatus = fromStatus;
        history.toStatus = toStatus;
        history.reason = reason;
        history.changedBy = changedBy;
        history.changedAt = OffsetDateTime.now();
        return history;
    }
}
