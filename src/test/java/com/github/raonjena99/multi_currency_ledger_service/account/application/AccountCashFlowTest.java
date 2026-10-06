package com.github.raonjena99.multi_currency_ledger_service.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.AccountStatus;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.AccountAlreadyExistsException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.AccountNotFoundException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.InsufficientBalanceException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.InvalidAccountStateException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.UnsupportedAssetCodeException;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;

/**
 * 계좌 개설과 법정화폐 입출금을 실제 DB 로 검증합니다.
 */
@DisplayName("계좌 개설 및 입출금")
class AccountCashFlowTest extends IntegrationTestSupport {

    @Autowired private AccountOpeningService openingService;
    @Autowired private AccountTradeFacade tradeFacade;
    @Autowired private AccountRepository accountRepository;
    @Autowired private MonthlyAccountLedgerRepository ledgerRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate txTemplate;

    @MockitoBean private ExchangeRateProvider exchangeRateProvider;

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events, transaction_entries, transactions, "
                + "monthly_account_ledgers, idempotency_records CASCADE");
        deleteTestAccounts();
    }

    private UUID openKrwAccount() {
        UUID accountId = UUID.randomUUID();
        openingService.open(accountId, "CASH_USER", "KRW");
        return accountId;
    }

    /** 읽기 경로와 같이 가장 최신 월 원장의 잔고를 읽는다. */
    private BigDecimal balanceOf(UUID accountId, String assetCode) {
        return latestLedger(accountId, assetCode).getBalance().getAmount();
    }

    private MonthlyAccountLedger latestLedger(UUID accountId, String assetCode) {
        String month = ledgerRepository.findLatestLedgerMonthByAccountId(accountId).orElseThrow();
        return ledgerRepository.findByAccountIdAndAssetCodeAndLedgerMonth(accountId, assetCode, month).orElseThrow();
    }

    private List<String> outboxPayloads() {
        return jdbcTemplate.queryForList("SELECT payload FROM outbox_events", String.class);
    }

    @Test
    @DisplayName("계좌를 개설하면 정상 상태의 계좌가 만들어진다")
    void openCreatesActiveAccount() {
        UUID accountId = UUID.randomUUID();

        openingService.open(accountId, "CASH_USER", "usd");

        Account account = accountRepository.findById(accountId).orElseThrow();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getBaseCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("이미 있는 계좌 ID 로는 개설할 수 없다")
    void openRejectsExistingId() {
        UUID accountId = openKrwAccount();

        assertThatThrownBy(() -> openingService.open(accountId, "OTHER", "KRW"))
                .isInstanceOf(AccountAlreadyExistsException.class);
        assertThat(accountRepository.findById(accountId).orElseThrow().getOwnerName()).isEqualTo("CASH_USER");
    }

    @Test
    @DisplayName("기준 통화가 법정화폐 코드가 아니면 개설할 수 없다")
    void openRejectsNonFiatBaseCurrency() {
        assertThatThrownBy(() -> openingService.open(UUID.randomUUID(), "CASH_USER", "BTC"))
                .isInstanceOf(UnsupportedAssetCodeException.class);
    }

    @Test
    @DisplayName("입금하면 잔고가 늘고 원장 기록 이벤트가 아웃박스에 남는다")
    void depositIncreasesBalanceAndQueuesLedgerEvent() {
        UUID accountId = openKrwAccount();

        UUID transactionId = tradeFacade.deposit("dep-1", accountId, "KRW", new BigDecimal("10000"));

        assertThat(balanceOf(accountId, "KRW")).isEqualByComparingTo("10000");
        assertThat(outboxPayloads()).anySatisfy(payload -> assertThat(payload)
                .contains("\"tradeId\":\"" + transactionId + "\"")
                .contains("\"tradeType\":\"DEPOSIT\""));
    }

    @Test
    @DisplayName("같은 멱등성 키로 다시 입금하면 같은 거래 ID 를 돌려주고 잔고는 한 번만 는다")
    void depositIsIdempotent() {
        UUID accountId = openKrwAccount();

        UUID first = tradeFacade.deposit("dep-1", accountId, "KRW", new BigDecimal("10000"));
        UUID second = tradeFacade.deposit("dep-1", accountId, "KRW", new BigDecimal("10000"));

        assertThat(second).isEqualTo(first);
        assertThat(balanceOf(accountId, "KRW")).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("출금하면 잔고가 줄고 원장 기록 이벤트가 아웃박스에 남는다")
    void withdrawalDecreasesBalance() {
        UUID accountId = openKrwAccount();
        tradeFacade.deposit("dep-1", accountId, "KRW", new BigDecimal("10000"));

        UUID transactionId = tradeFacade.withdraw("wd-1", accountId, "KRW", new BigDecimal("3000"));

        assertThat(balanceOf(accountId, "KRW")).isEqualByComparingTo("7000");
        assertThat(outboxPayloads()).anySatisfy(payload -> assertThat(payload)
                .contains("\"tradeId\":\"" + transactionId + "\"")
                .contains("\"tradeType\":\"WITHDRAWAL\""));
    }

    @Test
    @DisplayName("잔고보다 많이 출금하면 거부하고 잔고는 그대로다")
    void withdrawalBeyondBalanceIsRejected() {
        UUID accountId = openKrwAccount();
        tradeFacade.deposit("dep-1", accountId, "KRW", new BigDecimal("1000"));

        assertThatThrownBy(() -> tradeFacade.withdraw("wd-1", accountId, "KRW", new BigDecimal("1001")))
                .isInstanceOf(InsufficientBalanceException.class);
        assertThat(balanceOf(accountId, "KRW")).isEqualByComparingTo("1000");
        assertThat(outboxPayloads()).noneSatisfy(payload -> assertThat(payload).contains("WITHDRAWAL"));
    }

    @Test
    @DisplayName("정지된 계좌에는 입금할 수 없다")
    void depositToSuspendedAccountIsRejected() {
        UUID accountId = openKrwAccount();
        txTemplate.execute(status -> {
            accountRepository.findById(accountId).orElseThrow().suspend();
            return null;
        });

        assertThatThrownBy(() -> tradeFacade.deposit("dep-1", accountId, "KRW", new BigDecimal("1000")))
                .isInstanceOf(InvalidAccountStateException.class);
    }

    @Test
    @DisplayName("없는 계좌에는 입금할 수 없다")
    void depositToUnknownAccountIsRejected() {
        assertThatThrownBy(() -> tradeFacade.deposit("dep-1", UUID.randomUUID(), "KRW", new BigDecimal("1000")))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    @DisplayName("통화의 최소 단위보다 정밀한 금액은 반올림하지 않고 거부한다")
    void amountFinerThanCurrencyUnitIsRejected() {
        UUID accountId = openKrwAccount();

        // KRW 의 최소 단위는 1 원이다. 0.5 원을 반올림해 받으면 고객이 보낸 돈과 잔고가 달라진다.
        assertThatThrownBy(() -> tradeFacade.deposit("dep-1", accountId, "KRW", new BigDecimal("1000.5")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("법정화폐가 아닌 자산은 입금할 수 없다")
    void depositOfNonFiatIsRejected() {
        UUID accountId = openKrwAccount();

        assertThatThrownBy(() -> tradeFacade.deposit("dep-1", accountId, "BTC", new BigDecimal("1")))
                .isInstanceOf(UnsupportedAssetCodeException.class);
    }

    @Test
    @DisplayName("기준 통화가 아닌 통화로 입금하면 거래 시점 환율이 평균 단가와 원장 이벤트에 기록된다")
    void foreignCurrencyDepositUsesCurrentRate() {
        UUID accountId = openKrwAccount();
        org.mockito.Mockito.when(exchangeRateProvider.getExchangeRate("USD", "KRW"))
                .thenReturn(new ExchangeRateProvider.ExchangeRate(new BigDecimal("1300"), false));

        tradeFacade.deposit("dep-1", accountId, "USD", new BigDecimal("100"));

        MonthlyAccountLedger usd = latestLedger(accountId, "USD");
        assertThat(usd.getBalance().getAmount()).isEqualByComparingTo("100");
        assertThat(usd.getAverageUnitPrice()).isEqualByComparingTo("1300");
        assertThat(outboxPayloads()).anySatisfy(payload -> assertThat(payload)
                .contains("\"tradeType\":\"DEPOSIT\"")
                .contains("\"fiatToBaseRate\":1300"));
    }
}
