package com.github.raonjena99.multi_currency_ledger_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(args = "--server.port=0")
@ActiveProfiles("test")
class MultiCurrencyLedgerServiceApplicationMainTest {

    @Test
    void contextLoads() {
        MultiCurrencyLedgerServiceApplication.main(new String[]{"--server.port=0", "--spring.profiles.active=test"});
    }
}
