package com.github.raonjena99.multi_currency_ledger_service.account.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.raonjena99.multi_currency_ledger_service.account.application.AccountTradeFacade;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.GlobalExceptionHandler;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.security.AccountOwnershipGuard;

@DisplayName("단위 테스트: 거래 컨트롤러")
class AccountTradeControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private AccountTradeFacade accountTradeFacade;
    private AccountOwnershipGuard ownershipGuard;

    @BeforeEach
    void setUp() {
        accountTradeFacade = mock(AccountTradeFacade.class);
        ownershipGuard = mock(AccountOwnershipGuard.class);
        AccountTradeController controller = new AccountTradeController(accountTradeFacade, ownershipGuard);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                // 전역 예외 처리기를 함께 등록해 실제 응답 계약을 검증한다.
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
    }

    private AccountTradeController.TradeRequestDto validRequest() {
        return new AccountTradeController.TradeRequestDto(
                "idempotency-123", "BTC", AssetType.CRYPTO, "KRW",
                new BigDecimal("1.5"), new BigDecimal("10000000"));
    }

    @Test
    @DisplayName("매수 요청이 성공하면 거래 ID를 반환한다")
    void buyAsset_should_return_trade_id() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID tradeId = UUID.randomUUID();

        when(accountTradeFacade.buyAsset(anyString(), eq(accountId), anyString(), any(), anyString(), any(), any()))
                .thenReturn(tradeId);

        mockMvc.perform(post("/api/v1/accounts/{accountId}/trades/buy", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradeId").value(tradeId.toString()));

        verify(ownershipGuard).requireOwnership(accountId);
    }

    @Test
    @DisplayName("매도 요청이 성공하면 거래 ID를 반환한다")
    void sellAsset_should_return_trade_id() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID tradeId = UUID.randomUUID();

        when(accountTradeFacade.sellAsset(anyString(), eq(accountId), anyString(), any(), anyString(), any(), any()))
                .thenReturn(tradeId);

        mockMvc.perform(post("/api/v1/accounts/{accountId}/trades/sell", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradeId").value(tradeId.toString()));

        verify(ownershipGuard).requireOwnership(accountId);
    }

    @Test
    @DisplayName("남의 계좌로 거래하려 하면 거래가 실행되지 않는다")
    void rejects_trade_on_someone_elses_account() throws Exception {
        UUID accountId = UUID.randomUUID();

        doThrow(new AccessDeniedException("not yours"))
                .when(ownershipGuard).requireOwnership(accountId);

        try {
            mockMvc.perform(post("/api/v1/accounts/{accountId}/trades/buy", accountId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(validRequest())));
        } catch (Exception expected) {
            // standaloneSetup 에는 Security 필터 체인이 없으므로 예외가 그대로 전파될 수 있다.
            // 중요한 것은 소유권 검증 실패 시 거래가 실행되지 않는다는 점이다.
        }

        verify(accountTradeFacade, never())
                .buyAsset(anyString(), any(), anyString(), any(), anyString(), any(), any());
    }
}
