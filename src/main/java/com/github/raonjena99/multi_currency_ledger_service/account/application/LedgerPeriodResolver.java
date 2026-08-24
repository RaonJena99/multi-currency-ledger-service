package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 거래를 기장할 <b>실효 원장 월</b>을 결정합니다.
 *
 * <p>읽기 경로는 {@code MAX(ledger_month)} 행에서 잔고를 읽습니다. 그런데 쓰기 경로가 거래 시각의
 * 월을 그대로 쓰면, 그 월이 이미 존재하는 최신 월보다 과거일 때 <b>아무도 읽지 않는 행에 거래가
 * 기록되어 보고 잔고에서 조용히 사라집니다.</b> 그래서 쓰기는 읽기가 보는 행과 같은 월을 향해야 합니다.
 *
 * <p>실효 월 = {@code max(거래 시각의 월, 계좌에 존재하는 최신 원장 월)}
 *
 * <p><b>왜 거래를 거부하지 않는가.</b> 이 상황은 대개 다중 노드의 시계 편차가 월 경계에 걸릴 때
 * 발생합니다. 거부 정책을 택하면, 시계가 앞선 다른 노드가 미래 원장을 만들어 둔 탓에
 * <b>시계가 정확한 노드의 정상 거래가 거부</b>됩니다. 데이터 품질 문제를 가용성 장애로 바꾸는 셈입니다.
 *
 * <p>회계 관행과도 이 편이 맞습니다. 마감된 기간에 소급 기장하지 않는다는 원칙의 결론은
 * "거래를 거부한다"가 아니라 "현재 열린 기간에 기장한다"입니다. 거래의 진짜 시각은
 * {@code transactions.transacted_at} 에 그대로 보존되므로 감사 추적도 유지됩니다.
 */
@Slf4j
@Component
public class LedgerPeriodResolver {

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    private final MonthlyAccountLedgerRepository ledgerRepository;
    private final Counter backdatedCounter;

    public LedgerPeriodResolver(MonthlyAccountLedgerRepository ledgerRepository, MeterRegistry meterRegistry) {
        this.ledgerRepository = ledgerRepository;
        this.backdatedCounter = Counter.builder("ledger.period.backdated_write_redirected")
                .description("거래 시각의 월이 이미 존재하는 최신 원장 월보다 과거여서 최신 월로 귀속시킨 횟수. "
                        + "0 이 아니면 노드 간 시계 편차를 점검해야 합니다.")
                .register(meterRegistry);
    }

    /**
     * 거래를 기장할 실효 원장 월을 결정합니다.
     *
     * @param accountId    계좌 ID
     * @param transactedAt 거래 시각
     * @return {@code yyyy-MM} 형식의 실효 원장 월
     */
    public String resolveLedgerMonth(UUID accountId, OffsetDateTime transactedAt) {
        String monthOfTrade = transactedAt.format(MONTH_FORMATTER);

        String latestExisting = ledgerRepository.findLatestLedgerMonthByAccountId(accountId).orElse(null);
        if (latestExisting == null || latestExisting.compareTo(monthOfTrade) <= 0) {
            return monthOfTrade;
        }

        // ledger_month 는 zero-padding 된 yyyy-MM 이므로 사전순 비교가 곧 시간순 비교다.
        backdatedCounter.increment();
        log.warn("거래 시각의 월({})이 이미 존재하는 최신 원장 월({})보다 과거입니다. "
                        + "보고 잔고와 어긋나지 않도록 최신 월에 기장합니다. accountId={}. "
                        + "노드 간 시계 편차를 점검하십시오.",
                monthOfTrade, latestExisting, accountId);

        return latestExisting;
    }
}
