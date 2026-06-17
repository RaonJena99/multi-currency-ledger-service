package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlementId;

@Repository
public interface ExternalSettlementRepository extends JpaRepository<ExternalSettlement, ExternalSettlementId> {

    /**
     * [백오피스 관리자 수동 조회용] 단일 UUID 기준 단건 조회 (배치 사용 금지)
     * 주의: 엔티티 ID가 String 타입인 경우, 파라미터 타입을 String으로 변경해야 PostgreSQL 형불일치 에러가 발생하지 않음
     */
    @Query("SELECT e FROM ExternalSettlement e WHERE e.id = :id")
    Optional<ExternalSettlement> findByIdWithoutPartitionKey(@Param("id") UUID id); 
    
    /**
     * 기관 코드와 외부 참조 ID 기준 인덱스 조회
     */
    Optional<ExternalSettlement> findByInstitutionCodeAndExternalReferenceId(String institutionCode, String externalReferenceId);
}