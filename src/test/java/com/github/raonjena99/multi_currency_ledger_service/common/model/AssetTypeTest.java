package com.github.raonjena99.multi_currency_ledger_service.common.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class AssetTypeTest {

    @Test
    void normalize_nullValue() {
        assertThat(AssetType.FIAT.normalize(null)).isEqualTo(BigDecimal.ZERO);
    }
}
