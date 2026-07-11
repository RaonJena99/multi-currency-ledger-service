package com.github.raonjena99.multi_currency_ledger_service.account.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;

import jakarta.persistence.LockModeType;

/**
 * MonthlyAccountLedger(월별 계좌 원장) 엔티티에 대한 데이터 접근을 담당하는 Repository 인터페이스입니다.
 */
@Repository
public interface MonthlyAccountLedgerRepository extends JpaRepository<MonthlyAccountLedger, Long> {
    
    /**
     * Account(계좌) ID, 자산 코드, 대상 월을 기준으로 MonthlyAccountLedger(월별 계좌 원장)를 조회합니다.
     * 비즈니스 로직 처리 중 동시성 제어를 위해 Optimistic Lock(낙관적 락)을 적용합니다.
     *
     * @param accountId 계좌 ID
     * @param assetCode 자산 코드
     * @param ledgerMonth 대상 월
     * @return 해당하는 MonthlyAccountLedger(월별 계좌 원장) 객체 (Optional)
     */
    @Lock(LockModeType.OPTIMISTIC)
    Optional<MonthlyAccountLedger> findByAccountIdAndAssetCodeAndLedgerMonth(
        UUID accountId, String assetCode, String ledgerMonth
    );

    /**
     * 특정 Account(계좌) 및 자산에 대해, 가장 최근에 생성된 MonthlyAccountLedger(월별 계좌 원장)를 조회합니다.
     * 당월 원장이 없을 때, 이전 달의 장부 데이터를 이월(Carry-forward) 처리하기 위해 사용됩니다.
     *
     * @param accountId 계좌 ID
     * @param assetCode 자산 코드
     * @return 가장 최근의 MonthlyAccountLedger(월별 계좌 원장) 객체 (Optional)
     */
    Optional<MonthlyAccountLedger> findFirstByAccountIdAndAssetCodeOrderByLedgerMonthDesc(
        UUID accountId, String assetCode
    );

    /**
     * 특정 통화(AssetCode)에 대해, 전체 계좌들의 가장 최신 장부 잔고(Balance) 총합을 조회합니다.
     * 지연 이월(Lazy Carry-forward)로 인해 당월 장부가 없는 계좌도 포함하기 위해 서브 쿼리를 사용합니다.
     *
     * @param assetCode 자산 코드
     * @return 전체 계좌의 최신 잔고 총합 (BigDecimal)
     */
    @Query("""
        SELECT COALESCE(SUM(m.balance.amount), 0) 
        FROM MonthlyAccountLedger m 
        WHERE m.id IN (
            SELECT MAX(m2.id) 
            FROM MonthlyAccountLedger m2 
            WHERE m2.assetCode = :assetCode 
            GROUP BY m2.accountId
        )
    """)
    java.math.BigDecimal sumLatestBalanceByAssetCode(
        @org.springframework.data.repository.query.Param("assetCode") String assetCode
    );
}