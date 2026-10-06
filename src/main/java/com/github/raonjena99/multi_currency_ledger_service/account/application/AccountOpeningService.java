package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.AccountAlreadyExistsException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.UnsupportedAssetCodeException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 계좌를 개설합니다.
 *
 * <p>계좌 ID 는 호출한 쪽(관리자 또는 회원 서비스 같은 내부 시스템)이 정해서 보냅니다. 이 서비스는
 * 게이트웨이가 넣어 준 {@code X-Auth-Account-Id} 로 소유권을 판단하므로, 계좌 ID 와 고객의 연결은
 * 호출한 쪽이 관리해야 고객이 새 계좌에 접근할 수 있습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountOpeningService {

    private final AccountRepository accountRepository;

    /**
     * 계좌를 개설합니다.
     *
     * @throws UnsupportedAssetCodeException 기준 통화가 ISO 4217 법정화폐 코드가 아닌 경우
     * @throws AccountAlreadyExistsException 같은 ID 의 계좌가 이미 있는 경우
     */
    @Transactional
    public void open(UUID accountId, String ownerName, String baseCurrency) {
        String normalizedCurrency = baseCurrency == null ? null : baseCurrency.trim().toUpperCase();
        // 기준 통화는 평가·분개 금액의 단위다. 암호화폐나 오타가 들어가면 이후 모든 환산이 실패한다.
        if (!AccountTradeFacade.isIsoCurrency(normalizedCurrency)) {
            throw new UnsupportedAssetCodeException("기준 통화가 유효한 ISO 4217 코드가 아닙니다: " + baseCurrency);
        }
        if (accountRepository.existsById(accountId)) {
            throw new AccountAlreadyExistsException(accountId);
        }

        try {
            accountRepository.saveAndFlush(Account.open(accountId, ownerName, normalizedCurrency));
        } catch (DataIntegrityViolationException e) {
            // 같은 ID 로 동시에 개설을 요청해 존재 확인을 둘 다 통과한 경우
            throw new AccountAlreadyExistsException(accountId);
        }
        log.info("Account opened. accountId={}, baseCurrency={}", accountId, normalizedCurrency);
    }
}
