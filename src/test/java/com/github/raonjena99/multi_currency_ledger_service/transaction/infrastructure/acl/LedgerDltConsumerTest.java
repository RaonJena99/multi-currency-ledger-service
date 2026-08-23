package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.acl;

import org.junit.jupiter.api.Test;

class LedgerDltConsumerTest {

    @Test
    void consumeDlt_should_log_error() {
        LedgerDltConsumer consumer = new LedgerDltConsumer();
        consumer.consumeDlt("payload", "error", "topic");
        // Only logging is performed, no exception should be thrown
    }
}
