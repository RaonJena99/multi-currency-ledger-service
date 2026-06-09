package com.github.raonjena99.multi_currency_ledger_service.account.domain;

import java.util.UUID;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "owner_name", nullable = false, length = 100)
    private String ownerName;

    @Column(nullable = false, length = 20)
    private String status;

    public Account(UUID id, String ownerName) {
        this.id = id;
        this.ownerName = ownerName;
        this.status = "ACTIVE";
    }

}
