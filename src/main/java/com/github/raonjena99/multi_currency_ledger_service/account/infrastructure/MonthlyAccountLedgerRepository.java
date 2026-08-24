package com.github.raonjena99.multi_currency_ledger_service.account.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;

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
    Optional<MonthlyAccountLedger> findByAccountIdAndAssetCodeAndLedgerMonth(
        UUID accountId, String assetCode, String ledgerMonth
    );

    /**
     * 이월(Carry-forward) 원본이 될 <b>직전</b> 원장을 비관적 락과 함께 조회합니다.
     *
     * <p>{@code ledgerMonth < targetMonth} 필터가 핵심입니다. 필터 없이 "가장 최신 원장"을 가져오면,
     * 대상 월보다 <b>미래</b>의 원장이 이미 존재할 때 그 잔고를 과거 원장의 기초 잔고로 복사해
     * 버립니다(역방향 이월). 이월은 언제나 뒤만 봐야 합니다.
     *
     * @param accountId   계좌 ID
     * @param assetCode   자산 코드
     * @param targetMonth 초기화 대상 월. 이 월보다 <b>이전</b> 원장만 조회합니다.
     * @return 이월 원본이 될 직전 원장
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<MonthlyAccountLedger> findFirstWithLockByAccountIdAndAssetCodeAndLedgerMonthLessThanOrderByLedgerMonthDesc(
        UUID accountId, String assetCode, String targetMonth
    );

    /**
     * 해당 계좌에 존재하는 가장 최신 원장 월을 반환합니다.
     *
     * <p>읽기 경로({@link #findLatestBalancesByAccountId})가 {@code MAX(ledgerMonth)} 행을 읽으므로,
     * 쓰기도 그보다 과거 월에 들어가면 안 됩니다. 그 경우 거래가 아무도 읽지 않는 행에 기록되어
     * 보고 잔고에서 조용히 사라집니다. 자산별이 아니라 <b>계좌 단위</b>로 판단해야
     * 한 거래의 자산 원장과 법정화폐 원장이 같은 월에 놓입니다.
     *
     * @param accountId 계좌 ID
     * @return 가장 최신 원장 월. 원장이 하나도 없으면 비어 있음
     */
    @Query("SELECT MAX(m.ledgerMonth) FROM MonthlyAccountLedger m WHERE m.accountId = :accountId")
    Optional<String> findLatestLedgerMonthByAccountId(@Param("accountId") UUID accountId);

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
        WHERE m.assetCode = :assetCode
          AND m.ledgerMonth = (
            SELECT MAX(m2.ledgerMonth)
            FROM MonthlyAccountLedger m2
            WHERE m2.assetCode = m.assetCode
              AND m2.accountId = m.accountId
          )
    """)
    java.math.BigDecimal sumLatestBalanceByAssetCode(
        @Param("assetCode") String assetCode
    );

    /**
     * 장부에 기록된 고유한 법정 화폐(FIAT) 코드 목록을 조회합니다.
     * 
     * @return 법정 화폐 코드 리스트
     */
    @Query("SELECT DISTINCT m.assetCode FROM MonthlyAccountLedger m WHERE m.balance.assetType = 'FIAT'")
    java.util.List<String> findDistinctFiatCodes();

    /**
     * 특정 계좌의 모든 자산에 대한 가장 최신 장부 기록을 조회합니다.
     *
     * <p>"최신"의 기준은 반드시 {@code ledgerMonth} 여야 합니다. id 는 allocationSize=50 인
     * 풀드 시퀀스에서 발급되므로, 인스턴스가 둘 이상이면 각자 다른 id 구간을 선점해
     * <b>id 순서와 월 순서가 일치하지 않습니다.</b> MAX(id) 를 쓰면 나중에 만들어진 원장이
     * 더 작은 id 를 받아 지난달 잔고가 조회되는 조용한 오류가 발생합니다.
     *
     * <p>{@code ledger_month} 는 zero-padding 된 {@code yyyy-MM} 문자열이므로
     * 사전순 MAX 가 곧 시간순 최신입니다. idx_monthly_ledger_search 인덱스를 활용합니다.
     *
     * @param accountId 계좌 ID
     * @return 해당 계좌의 모든 자산에 대한 최신 MonthlyAccountLedger(월별 계좌 원장) 리스트
     */
    @Query("""
        SELECT m
        FROM MonthlyAccountLedger m
        WHERE m.accountId = :accountId
          AND m.ledgerMonth = (
            SELECT MAX(m2.ledgerMonth)
            FROM MonthlyAccountLedger m2
            WHERE m2.accountId = m.accountId
              AND m2.assetCode = m.assetCode
          )
    """)
    java.util.List<MonthlyAccountLedger> findLatestBalancesByAccountId(
        @Param("accountId") UUID accountId
    );
}