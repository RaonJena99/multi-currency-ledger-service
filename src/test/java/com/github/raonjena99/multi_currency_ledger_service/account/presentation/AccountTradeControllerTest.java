package com.github.raonjena99.multi_currency_ledger_service.account.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.raonjena99.multi_currency_ledger_service.account.application.AccountTradeFacade;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

class AccountTradeControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private AccountTradeFacade accountTradeFacade;

    @BeforeEach
    void setUp() {
        accountTradeFacade = mock(AccountTradeFacade.class);
        AccountTradeController controller = new AccountTradeController(accountTradeFacade);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper();
    }

    @Test
    void buyAsset_should_return_trade_id() throws Exception {
        UUID accountId = UUID.randomUUID();
        AccountTradeController.TradeRequestDto request = new AccountTradeController.TradeRequestDto(
                "idempotency-123", "BTC", AssetType.CRYPTO, "KRW", new BigDecimal("1.5"), new BigDecimal("10000000")
        );

        UUID tradeId = UUID.randomUUID();
        when(accountTradeFacade.buyAsset(
                eq("idempotency-123"), eq(accountId), eq("BTC"), eq(AssetType.CRYPTO), eq("KRW"),
                any(), eq(new BigDecimal("10000000"))
        )).thenReturn(tradeId);

        mockMvc.perform(post("/api/v1/accounts/{accountId}/trades/buy", accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradeId").value(tradeId.toString()));
    }
    
    @Test
    void sellAsset_should_return_trade_id() throws Exception {
        UUID accountId = UUID.randomUUID();
        AccountTradeController.TradeRequestDto request = new AccountTradeController.TradeRequestDto(
                "idempotency-123", "BTC", AssetType.CRYPTO, "KRW", new BigDecimal("1.5"), new BigDecimal("10000000")
        );

        UUID tradeId = UUID.randomUUID();
        when(accountTradeFacade.sellAsset(
                eq("idempotency-123"), eq(accountId), eq("BTC"), eq(AssetType.CRYPTO), eq("KRW"),
                any(), eq(new BigDecimal("10000000"))
        )).thenReturn(tradeId);

        mockMvc.perform(post("/api/v1/accounts/{accountId}/trades/sell", accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradeId").value(tradeId.toString()));
    }
}
