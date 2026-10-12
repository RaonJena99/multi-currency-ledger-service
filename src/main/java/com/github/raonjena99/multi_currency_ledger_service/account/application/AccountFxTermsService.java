package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.AccountNotFoundException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 계좌별 환전 조건(우대율)을 조회하고 바꿉니다.
 *
 * <p>우대율은 회사의 스프레드 수익을 깎는 값이므로 변경은 관리자 API 로만 열려 있고, 누가 언제 어떤 값으로 바꿨는지
 * 로그에 남깁니다. 설정의 상한({@code ledger.fx.max-preferential-rate})을 넘는 값은 저장하지 않습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountFxTermsService {

    private final AccountRepository accountRepository;
    private final FxProperties fxProperties;

    /**
     * 계좌에 적용되는 우대율.
     *
     * @param rate     실제로 적용되는 우대율. 지정하지 않은 계좌는 기본 우대율이다
     * @param explicit 계좌에 지정한 값이면 true, 기본 우대율을 쓰면 false
     */
    public record PreferentialRate(BigDecimal rate, boolean explicit) {
    }

    /**
     * 계좌에 적용되는 우대율을 조회합니다.
     *
     * @throws AccountNotFoundException 계좌가 없는 경우
     */
    @Transactional(readOnly = true)
    public PreferentialRate preferentialRateOf(UUID accountId) {
        return toView(findAccount(accountId));
    }

    /**
     * 계좌의 우대율을 지정합니다.
     *
     * @param accountId 계좌 ID
     * @param rate      우대율. 0 이상이고 설정의 상한 이하
     * @param changedBy 변경한 관리자 식별자(로그용)
     * @throws IllegalArgumentException 상한을 넘거나 유효 범위를 벗어난 경우
     * @throws AccountNotFoundException 계좌가 없는 경우
     */
    @Transactional
    public PreferentialRate changePreferentialRate(UUID accountId, BigDecimal rate, String changedBy) {
        if (rate == null) {
            throw new IllegalArgumentException("우대율이 필요합니다.");
        }
        BigDecimal max = fxProperties.getMaxPreferentialRate();
        if (rate.compareTo(max) > 0) {
            throw new IllegalArgumentException(String.format(
                    "우대율 %s 이 상한 %s 을 넘습니다.", rate.toPlainString(), max.toPlainString()));
        }

        Account account = findAccount(accountId);
        BigDecimal before = account.getFxPreferentialRate();
        account.changeFxPreferentialRate(rate);

        log.info("환전 우대율을 바꿨습니다. accountId={}, {} -> {}, changedBy={}",
                accountId, before != null ? before.toPlainString() : "미지정", rate.toPlainString(), changedBy);
        return toView(account);
    }

    private Account findAccount(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private PreferentialRate toView(Account account) {
        BigDecimal stored = account.getFxPreferentialRate();
        return new PreferentialRate(fxProperties.resolvePreferentialRate(stored), stored != null);
    }
}
