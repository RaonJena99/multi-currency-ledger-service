package com.github.raonjena99.multi_currency_ledger_service.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch.SettlementMatchRecorder;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch.SettlementMatchRecorder.MatchOutcome;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.SettlementMatch;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.SettlementMatchRepository;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.query.InternalTransactionQueryDao;

/**
 * 정산 ↔ 내부 거래 매칭의 무결성 계약을 고정합니다.
 *
 * <p>이 테스트가 존재하는 이유는 앞선 검증이 <b>제약조건이 DB에 있는지만</b> 확인하고
 * <b>애플리케이션 경로가 그 제약에 실제로 걸리는지</b>는 확인하지 않았기 때문입니다.
 * 외부에서 ID를 할당하는 엔티티는 {@code save()} 가 {@code merge()} 경로를 타서
 * 제약조건에 도달하기 전에 기존 행을 <b>조용히 UPDATE</b> 해버립니다. raw JDBC INSERT 로만
 * 검증하면 이 구멍이 그대로 남습니다.
 */
@DisplayName("회귀 테스트: 정산 매칭 무결성")
class SettlementMatchIntegrityTest extends IntegrationTestSupport {

    @Autowired private SettlementMatchRecorder recorder;
    @Autowired private SettlementMatchRepository matchRepository;
    @Autowired private InternalTransactionQueryDao queryDao;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate txTemplate;

    @AfterEach
    void tearDown() {
        jdbc.execute("TRUNCATE TABLE settlement_match, transaction_entries, transactions, "
                + "external_settlement CASCADE");
        deleteTestAccounts();
    }

    private UUID matchedSettlementOf(UUID internalTxId) {
        var rows = jdbc.queryForList(
                "SELECT external_settlement_id FROM settlement_match WHERE internal_transaction_id = ?", internalTxId);
        return rows.isEmpty() ? null : UUID.fromString(String.valueOf(rows.get(0).get("external_settlement_id")));
    }

    @Test
    @DisplayName("다른 정산이 같은 내부 거래를 가져가려 하면 기존 매칭을 덮어쓰지 않고 거부한다")
    void does_not_silently_overwrite_an_existing_match() {
        UUID internalTx = UUID.randomUUID();
        UUID settlementA = UUID.randomUUID();
        UUID settlementB = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        assertThat(recorder.recordMatch(SettlementMatch.of(internalTx, settlementA, now)))
                .isEqualTo(MatchOutcome.RECORDED);

        // 핵심: JPA save() 가 merge 경로를 타면 예외 없이 external_settlement_id 를 B 로 덮어쓴다.
        MatchOutcome second = recorder.recordMatch(SettlementMatch.of(internalTx, settlementB, now));

        assertThat(second)
                .as("다른 정산의 선점 시도는 TAKEN_BY_ANOTHER 로 판정되어야 한다")
                .isEqualTo(MatchOutcome.TAKEN_BY_ANOTHER);
        assertThat(second.isMatchable()).isFalse();

        assertThat(matchedSettlementOf(internalTx))
                .as("기존 매칭(A)이 그대로 유지되어야 한다. B 로 바뀌면 무결성이 훼손된 것이다")
                .isEqualTo(settlementA);
    }

    @Test
    @DisplayName("같은 정산으로 다시 기록하면 재실행으로 보고 성공 처리한다")
    void re_recording_the_same_match_is_idempotent() {
        UUID internalTx = UUID.randomUUID();
        UUID settlement = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        assertThat(recorder.recordMatch(SettlementMatch.of(internalTx, settlement, now)))
                .isEqualTo(MatchOutcome.RECORDED);

        // faultTolerant 스텝은 청크 롤백 후 건별로 재실행한다. 매칭 행은 독립 트랜잭션에서
        // 이미 커밋되어 남아 있으므로, 재실행이 자기 행 때문에 막히면 정산은 영구히 매칭되지 못한다.
        MatchOutcome rerun = recorder.recordMatch(SettlementMatch.of(internalTx, settlement, now));

        assertThat(rerun).isEqualTo(MatchOutcome.ALREADY_RECORDED);
        assertThat(rerun.isMatchable())
                .as("재실행은 정산 상태 전이를 이어서 마무리할 수 있어야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("중복 매칭 예외가 호출자의 트랜잭션을 오염시키지 않는다")
    void conflict_does_not_poison_the_caller_transaction() {
        UUID taken = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        recorder.recordMatch(SettlementMatch.of(taken, UUID.randomUUID(), now));

        Boolean pollutedByConflict = txTemplate.execute(status -> {
            // 같은 청크 안의 정상 건
            recorder.recordMatch(SettlementMatch.of(fresh, UUID.randomUUID(), now));
            // 그리고 충돌 건
            recorder.recordMatch(SettlementMatch.of(taken, UUID.randomUUID(), now));
            // 호출자 트랜잭션이 rollback-only 로 마킹되면 청크 전체가 통째로 롤백된다.
            return status.isRollbackOnly();
        });

        assertThat(pollutedByConflict)
                .as("독립 트랜잭션이 아니면 제약조건 위반이 청크 전체를 롤백시킨다")
                .isFalse();
        assertThat(matchedSettlementOf(fresh))
                .as("같은 청크의 정상 건이 살아남아야 한다")
                .isNotNull();
    }

    @Test
    @DisplayName("청크 롤백으로 남은 고아 매칭 행이 내부 거래를 후보에서 가리지 않는다")
    void orphan_match_row_does_not_hide_the_candidate() {
        UUID accountId = UUID.randomUUID();
        UUID internalTx = UUID.randomUUID();
        OffsetDateTime tradedAt = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);

        jdbc.update("INSERT INTO accounts (id, owner_name, status, base_currency, created_at, updated_at) "
                + "VALUES (?, 'ORPHAN', 'ACTIVE', 'KRW', now(), now())", accountId);
        jdbc.update("INSERT INTO transactions (id, transaction_type, description, transacted_at) "
                + "VALUES (?, 'SELL', 'orphan probe', ?)", internalTx, tradedAt);
        jdbc.update("INSERT INTO transaction_entries "
                + "(transaction_id, account_id, entry_type, asset_code, quantity, quantity_asset_type, quantity_currency, "
                + " unit_price, exchange_rate, amount, amount_asset_type, amount_currency, "
                + " realized_pnl, realized_pnl_asset_type, realized_pnl_currency, created_at) "
                + "VALUES (?, ?, 'CREDIT', 'BTC', 1, 'CRYPTO', 'BTC', 1000, 1, 1000, 'FIAT', 'KRW', 0, 'FIAT', 'KRW', now())",
                internalTx, accountId);

        // 후보로 보여야 한다 (정상 상태)
        var before = queryDao.fetchCandidatesForPeriod(tradedAt.minusDays(1), tradedAt.plusDays(1));
        assertThat(before).extracting(c -> c.transactionId()).contains(internalTx);

        // 청크가 롤백되어 매칭 행만 남은 고아 상태를 재현한다.
        // 정산 쪽 상태는 갱신되지 않았으므로 이 내부 거래는 여전히 매칭 가능해야 한다.
        recorder.recordMatch(SettlementMatch.of(internalTx, UUID.randomUUID(), OffsetDateTime.now()));

        var after = queryDao.fetchCandidatesForPeriod(tradedAt.minusDays(1), tradedAt.plusDays(1));
        assertThat(after).extracting(c -> c.transactionId())
                .as("후보 가시성이 settlement_match 에 의존하면 고아 행이 이 거래를 영구히 가려버린다. "
                        + "판단 기준은 정산 상태와 같은 트랜잭션에서 갱신되는 "
                        + "external_settlement.matched_internal_transaction_id 여야 한다")
                .contains(internalTx);
    }

    @Test
    @DisplayName("엔티티는 항상 INSERT 를 수행해 merge 로 인한 덮어쓰기를 원천 차단한다")
    void entity_always_inserts() {
        SettlementMatch match = SettlementMatch.of(UUID.randomUUID(), UUID.randomUUID(), OffsetDateTime.now());

        assertThat(match.isNew())
                .as("isNew()=false 이면 save() 가 merge 경로를 타 기존 행을 조용히 UPDATE 한다")
                .isTrue();

        SettlementMatch saved = matchRepository.saveAndFlush(match);
        assertThat(saved.isNew())
                .as("영속화 이후에는 신규가 아니어야 한다")
                .isFalse();

        assertThat(matchRepository.findById(saved.getInternalTransactionId()))
                .isPresent()
                .get()
                .satisfies(loaded -> assertThat(loaded.isNew())
                        .as("조회된 엔티티도 신규가 아니어야 한다")
                        .isFalse());
    }
}
