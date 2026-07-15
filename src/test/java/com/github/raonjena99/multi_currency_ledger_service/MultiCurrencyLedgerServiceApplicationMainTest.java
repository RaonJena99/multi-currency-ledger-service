package com.github.raonjena99.multi_currency_ledger_service;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.boot.SpringApplication;

class MultiCurrencyLedgerServiceApplicationMainTest {

    @Test
    void main() {
        try (MockedStatic<SpringApplication> mocked = Mockito.mockStatic(SpringApplication.class)) {
            MultiCurrencyLedgerServiceApplication.main(new String[]{});
            mocked.verify(() -> SpringApplication.run(MultiCurrencyLedgerServiceApplication.class, new String[]{}));
        }
    }
}
