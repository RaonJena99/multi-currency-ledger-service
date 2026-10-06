package com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.ingestion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.github.raonjena99.multi_currency_ledger_service.common.domain.CurrencyScaleResolver;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.adapter.ExternalSettlementDto;

import lombok.extern.slf4j.Slf4j;

/**
 * PG 가 내려준 정산 내역 CSV 를 적재합니다.
 *
 * <p>개인이 접속할 수 있는 PG 정산 API 가 없어 대사 배치에 넣을 데이터가 없었습니다. 실무에서 흔한
 * "정산 내역을 파일로 내려받는" 방식으로 적재 경로를 엽니다. 행마다 {@link SettlementRecorder} 로 적재하므로
 * PG API 경로와 같은 규칙(실수령액 = 금액 − 수수료, 같은 거래 ID 는 한 번만)을 따릅니다.
 *
 * <p><b>형식</b>: 첫 행은 머리글 {@value #HEADER} 입니다. 쉼표로 구분하며 따옴표로 감싼 값은 지원하지 않습니다
 * (값 안에 쉼표가 들어갈 필드가 없습니다).
 *
 * <p>잘못된 행은 행 번호와 사유를 결과에 담고 나머지 행은 계속 적재합니다. 반면 머리글이 다르거나 행 수가
 * 상한을 넘으면 한 건도 적재하지 않고 파일 전체를 거부합니다. 일부만 적재된 채로 끝나면 어디까지 들어갔는지
 * 알기 어렵기 때문입니다.
 */
@Slf4j
@Service
public class SettlementCsvIngestionService {

    static final String HEADER = "transactionId,currency,amount,fee,status,settledAt";
    private static final int COLUMNS = 6;
    /** external_settlement.external_reference_id 컬럼 길이 */
    private static final int MAX_TRANSACTION_ID_LENGTH = 100;

    private final SettlementRecorder recorder;
    private final int maxRows;

    public record RowFailure(int line, String transactionId, String reason) {}

    public record IngestionResult(int totalRows, int ingested, int duplicates, List<RowFailure> failures) {}

    public SettlementCsvIngestionService(SettlementRecorder recorder,
                                         @Value("${ledger.reconciliation.csv.max-rows:10000}") int maxRows) {
        this.recorder = recorder;
        this.maxRows = maxRows;
    }

    /**
     * CSV 를 읽어 적재합니다.
     *
     * @throws IllegalArgumentException 머리글이 다르거나 행 수가 상한을 넘은 경우 (한 건도 적재하지 않음)
     */
    public IngestionResult ingest(InputStream csv) {
        List<String> lines = readLines(csv);
        if (lines.isEmpty() || !HEADER.equals(stripBom(lines.get(0)).trim())) {
            throw new IllegalArgumentException("첫 행은 머리글 '" + HEADER + "' 이어야 합니다.");
        }

        List<NumberedLine> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                rows.add(new NumberedLine(i + 1, lines.get(i)));
            }
        }
        if (rows.size() > maxRows) {
            throw new IllegalArgumentException("행 수 " + rows.size() + " 가 상한 " + maxRows + " 를 넘습니다. 파일을 나눠 올리십시오.");
        }

        int ingested = 0;
        int duplicates = 0;
        List<RowFailure> failures = new ArrayList<>();
        for (NumberedLine row : rows) {
            String[] cells = row.text().split(",", -1);
            String transactionId = cells[0].trim();
            try {
                ExternalSettlementDto dto = parse(cells);
                if (recorder.recordSettlement(dto)) {
                    ingested++;
                } else {
                    duplicates++;
                }
            } catch (IllegalArgumentException | DateTimeParseException e) {
                failures.add(new RowFailure(row.number(), transactionId, e.getMessage()));
            } catch (RuntimeException e) {
                log.warn("정산 행 적재 실패. line={}, transactionId={}", row.number(), transactionId, e);
                failures.add(new RowFailure(row.number(), transactionId, "적재 실패: " + e.getClass().getSimpleName()));
            }
        }

        log.info("정산 CSV 적재 완료. rows={}, ingested={}, duplicates={}, failures={}",
                rows.size(), ingested, duplicates, failures.size());
        return new IngestionResult(rows.size(), ingested, duplicates, failures);
    }

    private ExternalSettlementDto parse(String[] cells) {
        if (cells.length != COLUMNS) {
            throw new IllegalArgumentException("열이 " + COLUMNS + "개여야 하는데 " + cells.length + "개입니다.");
        }
        String transactionId = cells[0].trim();
        if (transactionId.isEmpty() || transactionId.length() > MAX_TRANSACTION_ID_LENGTH) {
            throw new IllegalArgumentException("transactionId 는 1~" + MAX_TRANSACTION_ID_LENGTH + "자여야 합니다.");
        }
        String currency = cells[1].trim().toUpperCase();
        try {
            Currency.getInstance(currency);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("currency 가 법정화폐(ISO 4217) 코드가 아닙니다: " + cells[1].trim());
        }
        BigDecimal amount = decimal("amount", cells[2]);
        BigDecimal fee = decimal("fee", cells[3]);
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount 는 0보다 커야 합니다.");
        }
        if (fee.signum() < 0 || fee.compareTo(amount) > 0) {
            throw new IllegalArgumentException("fee 는 0 이상, amount 이하여야 합니다.");
        }
        int scale = CurrencyScaleResolver.resolveScale(AssetType.FIAT, currency);
        if (amount.stripTrailingZeros().scale() > scale || fee.stripTrailingZeros().scale() > scale) {
            // 반올림해 받으면 PG 가 보낸 금액과 달라져 대사 금액 비교가 틀어진다.
            throw new IllegalArgumentException("금액이 " + currency + " 최소 단위보다 정밀합니다.");
        }
        String status = cells[4].trim();
        if (status.isEmpty()) {
            throw new IllegalArgumentException("status 가 비어 있습니다.");
        }
        OffsetDateTime settledAt;
        try {
            settledAt = OffsetDateTime.parse(cells[5].trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("settledAt 은 시간대를 포함한 ISO-8601 형식이어야 합니다(예: 2026-06-15T10:00:00Z).");
        }
        return new ExternalSettlementDto(transactionId, currency, amount, fee, status, settledAt);
    }

    private static BigDecimal decimal(String name, String value) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 가 숫자가 아닙니다: " + value.trim());
        }
    }

    private static List<String> readLines(InputStream csv) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csv, StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 엑셀 등에서 저장한 UTF-8 파일은 맨 앞에 BOM 이 붙는다. */
    private static String stripBom(String line) {
        return line.startsWith("﻿") ? line.substring(1) : line;
    }

    private record NumberedLine(int number, String text) {}
}
