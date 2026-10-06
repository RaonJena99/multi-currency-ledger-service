package com.github.raonjena99.multi_currency_ledger_service.portfolio.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.AccountApi;
import com.github.raonjena99.multi_currency_ledger_service.account.AccountApi.AccountBalanceDto;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.BalanceAdjustedEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioCacheDto;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.port.PortfolioCachePort;

/**
 * 캐시를 다시 채우는 도중에 다른 거래가 커밋되면, 다시 채우던 쪽이 거래 이전 잔고를 캐시에 쓰면 안 됩니다.
 *
 * <p>실제 Redis 로 검증합니다. 캐시 포트를 목으로 바꾸면 "DB 를 읽은 뒤 다른 커밋이 캐시를 지우고,
 * 그 뒤에 옛 값을 쓴다"는 순서 역전을 재현할 수 없습니다.
 */
@DisplayName("포트폴리오 캐시: 거래 이전 잔고 재기록 방지")
class PortfolioCacheStaleWriteTest extends IntegrationTestSupport {

    @Autowired private PortfolioCachePort cachePort;

    private final UUID accountId = UUID.randomUUID();
    private final AccountApi accountApi = mock(AccountApi.class);
    private final List<AccountBalanceDto> balancesBeforeWithdrawal =
            List.of(new AccountBalanceDto("KRW", new BigDecimal("10000"), BigDecimal.ONE, "KRW"));

    @BeforeEach
    void setUp() {
        when(accountApi.getBaseCurrency(accountId)).thenReturn("KRW");
    }

    @AfterEach
    void tearDown() {
        cachePort.evictPortfolioCache(accountId);
    }

    /** 잔고를 읽어 간 직후, 캐시에 쓰기 전에 다른 거래(출금)가 커밋되어 캐시를 지운 상황. */
    private void withdrawalCommitsRightAfterBalancesAreRead() {
        when(accountApi.getBalances(accountId)).thenAnswer(invocation -> {
            cachePort.evictPortfolioCache(accountId);
            return balancesBeforeWithdrawal;
        });
    }

    @Test
    @DisplayName("비동기 갱신 도중 다른 거래가 커밋되면 갱신 결과를 캐시에 쓰지 않는다")
    void refresherDoesNotWriteSnapshotOverNewerCommit() {
        withdrawalCommitsRightAfterBalancesAreRead();
        PortfolioViewRefresher refresher = new PortfolioViewRefresher(cachePort, accountApi);

        refresher.onBalanceAdjusted(new BalanceAdjustedEvent(accountId, Money.of("1", AssetType.FIAT, "KRW"), null));

        assertThat(cachePort.getPortfolioCache(accountId)).isEmpty();
    }

    @Test
    @DisplayName("조회 경로가 캐시를 다시 채우는 도중 다른 거래가 커밋되면 캐시에 쓰지 않는다")
    void queryDoesNotWriteSnapshotOverNewerCommit() {
        withdrawalCommitsRightAfterBalancesAreRead();
        PortfolioQueryService queryService =
                new PortfolioQueryService(mock(ExchangeRateProvider.class), accountApi, cachePort);

        queryService.getPortfolioSummary(accountId);

        assertThat(cachePort.getPortfolioCache(accountId)).isEmpty();
    }

    private PortfolioCacheDto snapshot(String krw) {
        return new PortfolioCacheDto(accountId, "KRW", new java.util.ArrayList<>(List.of(
                new PortfolioCacheDto.AssetBalance("KRW", new BigDecimal(krw), BigDecimal.ONE, "KRW"))));
    }

    @Test
    @DisplayName("캐시를 지우면 세대가 1 오르고 캐시가 비워진다")
    void evictBumpsGenerationAndDeletesCache() {
        long before = cachePort.currentGeneration(accountId);
        cachePort.savePortfolioCacheIfGeneration(accountId, snapshot("10000"), before);

        cachePort.evictPortfolioCache(accountId);

        assertThat(cachePort.currentGeneration(accountId)).isEqualTo(before + 1);
        assertThat(cachePort.getPortfolioCache(accountId)).isEmpty();
    }

    @Test
    @DisplayName("받아 둔 세대가 지금 세대와 다르면 쓰지 않는다")
    void saveWithStaleGenerationIsRejected() {
        long generation = cachePort.currentGeneration(accountId);
        cachePort.evictPortfolioCache(accountId);

        boolean saved = cachePort.savePortfolioCacheIfGeneration(accountId, snapshot("10000"), generation);

        assertThat(saved).isFalse();
        assertThat(cachePort.getPortfolioCache(accountId)).isEmpty();
    }

    @Test
    @DisplayName("받아 둔 세대가 그대로면 쓰고, 조회 경로가 그대로 읽을 수 있다")
    void saveWithCurrentGenerationIsReadable() {
        long generation = cachePort.currentGeneration(accountId);

        boolean saved = cachePort.savePortfolioCacheIfGeneration(accountId, snapshot("7000"), generation);

        assertThat(saved).isTrue();
        assertThat(cachePort.getPortfolioCache(accountId)).hasValueSatisfying(cached ->
                assertThat(cached.getBalances().get(0).getTotalQuantity()).isEqualByComparingTo("7000"));
    }

    @Test
    @DisplayName("그사이 다른 거래가 없으면 갱신 결과를 캐시에 쓴다")
    void refresherWritesWhenNothingChanged() {
        when(accountApi.getBalances(accountId)).thenReturn(balancesBeforeWithdrawal);
        PortfolioViewRefresher refresher = new PortfolioViewRefresher(cachePort, accountApi);

        refresher.onBalanceAdjusted(new BalanceAdjustedEvent(accountId, Money.of("1", AssetType.FIAT, "KRW"), null));

        assertThat(cachePort.getPortfolioCache(accountId)).hasValueSatisfying(cached ->
                assertThat(cached.getBalances().get(0).getTotalQuantity()).isEqualByComparingTo("10000"));
    }
}
