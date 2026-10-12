package com.github.raonjena99.multi_currency_ledger_service.account.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxConversion;

public interface FxConversionRepository extends JpaRepository<FxConversion, UUID> {

    /** 견적으로 체결 기록을 찾는다. 견적은 한 번만 체결되므로 최대 하나다. */
    Optional<FxConversion> findByQuoteId(UUID quoteId);
}
