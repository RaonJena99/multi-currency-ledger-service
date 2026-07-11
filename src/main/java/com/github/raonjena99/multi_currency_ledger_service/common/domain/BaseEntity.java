package com.github.raonjena99.multi_currency_ledger_service.common.domain;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

/**
 * 모든 도메인 엔티티의 공통 매핑 정보를 제공하는 BaseEntity(기본 엔티티) 클래스입니다.
 * 생성 일시(createdAt)와 수정 일시(updatedAt)를 자동으로 관리합니다.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(updatable = false, nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;
    
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime updatedAt;
}