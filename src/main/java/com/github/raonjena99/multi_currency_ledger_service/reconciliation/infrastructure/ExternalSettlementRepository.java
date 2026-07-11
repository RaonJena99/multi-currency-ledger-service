package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlementId;

/**
 * ExternalSettlement(외부 정산) 엔티티를 관리하는 JPA 리포지토리(Repository)입니다.
 */
@Repository
public interface ExternalSettlementRepository extends JpaRepository<ExternalSettlement, ExternalSettlementId> {

    /**
     * [백오피스 관리자 수동 조회용] 파티션 키 없이 단일 UUID를 기준으로 정산 데이터를 조회합니다. (배치 처리 시 사용을 권장하지 않습니다.)
     * 
     * @param id 조회할 외부 정산 데이터의 고유 UUID
     * @return 조회된 정산 데이터 (Optional<ExternalSettlement>)
     */
    @Query("SELECT e FROM ExternalSettlement e WHERE e.id = :id")
    Optional<ExternalSettlement> findByIdWithoutPartitionKey(@Param("id") UUID id); 
    
    /**
     * 기관 코드와 외부 참조 ID를 조합하여 정산 데이터를 조회합니다.
     * 
     * @param institutionCode 기관 코드
     * @param externalReferenceId 외부 시스템의 참조 ID
     * @return 조회된 정산 데이터 (Optional<ExternalSettlement>)
     */
    Optional<ExternalSettlement> findByInstitutionCodeAndExternalReferenceId(String institutionCode, String externalReferenceId);
}