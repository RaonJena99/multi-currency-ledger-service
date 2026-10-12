package com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.acl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxDirection;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.FxConversionDetail;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.TradeType;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRepository;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class AccountOutboxAclTest {

    @Mock private OutboxRepository outboxRepository;
    @Mock private JsonMapper jsonMapper;

    @InjectMocks private AccountOutboxAcl acl;

    @Test
    void persistOutboxEvent() throws Exception {
        TradeExecutedEvent event = new TradeExecutedEvent(UUID.randomUUID(), UUID.randomUUID(), "KRW", com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType.FIAT, "USD", "KRW", com.github.raonjena99.multi_currency_ledger_service.common.model.TradeType.BUY, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, false, java.time.OffsetDateTime.now());
        when(jsonMapper.writeValueAsString(any())).thenReturn("{}");
        acl.persistOutboxEvent(event);
        verify(outboxRepository).save(any());
    }

    @Test
    void persistOutboxEvent_exception() throws Exception {
        TradeExecutedEvent event = new TradeExecutedEvent(UUID.randomUUID(), UUID.randomUUID(), "KRW", com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType.FIAT, "USD", "KRW", com.github.raonjena99.multi_currency_ledger_service.common.model.TradeType.BUY, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, false, java.time.OffsetDateTime.now());
        when(jsonMapper.writeValueAsString(any())).thenThrow(new RuntimeException("error"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> acl.persistOutboxEvent(event))
            .isInstanceOf(RuntimeException.class);
    }

    private TradeExecutedEvent conversion(FxConversionDetail fx) {
        return new TradeExecutedEvent(UUID.randomUUID(), UUID.randomUUID(), "USD", AssetType.FIAT, "KRW", "KRW",
                TradeType.FX_CONVERSION, new BigDecimal("1000"), new BigDecimal("1322.75"), new BigDecimal("1300"),
                BigDecimal.ONE, BigDecimal.ONE, false, java.time.OffsetDateTime.now(), fx);
    }

    private AccountOutboxAcl.LedgerRecordingPayload persistedPayload(TradeExecutedEvent event) throws Exception {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        when(jsonMapper.writeValueAsString(captor.capture())).thenReturn("{}");
        acl.persistOutboxEvent(event);
        return (AccountOutboxAcl.LedgerRecordingPayload) captor.getValue();
    }

    @Test
    @DisplayName("환전 이벤트의 분개 금액(방향, 기준 통화 금액, 시세 가치)이 아웃박스 페이로드에 실린다")
    void conversionDetailIsCarriedIntoThePayload() throws Exception {
        AccountOutboxAcl.LedgerRecordingPayload payload = persistedPayload(conversion(
                new FxConversionDetail(FxDirection.BUY_FOREIGN, new BigDecimal("1322750"), new BigDecimal("1300000"))));

        assertThat(payload.tradeType()).isEqualTo("FX_CONVERSION");
        assertThat(payload.fx()).isNotNull();
        assertThat(payload.fx().direction()).isEqualTo("BUY_FOREIGN");
        assertThat(payload.fx().baseAmount()).isEqualByComparingTo("1322750");
        assertThat(payload.fx().marketValue()).isEqualByComparingTo("1300000");
    }

    @Test
    @DisplayName("환전이 아닌 이벤트의 페이로드에는 환전 상세가 없다")
    void nonConversionPayloadsHaveNoFxDetail() throws Exception {
        TradeExecutedEvent deposit = new TradeExecutedEvent(UUID.randomUUID(), UUID.randomUUID(), "KRW", AssetType.FIAT,
                "KRW", "KRW", TradeType.DEPOSIT, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, false, java.time.OffsetDateTime.now());

        assertThat(persistedPayload(deposit).fx()).isNull();
    }
}
