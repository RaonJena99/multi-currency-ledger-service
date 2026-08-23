package com.github.raonjena99.multi_currency_ledger_service.common.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AssetTypeTest {

    @Test
    void getDefaultScale_should_return_correct_scale() {
        assertThat(AssetType.FIAT.getDefaultScale()).isEqualTo(4);
        assertThat(AssetType.STOCK.getDefaultScale()).isEqualTo(8);
        assertThat(AssetType.CRYPTO.getDefaultScale()).isEqualTo(18);
        assertThat(AssetType.POINT.getDefaultScale()).isEqualTo(0);
    }

    @Test
    void isDigitalAsset_should_return_true_only_for_crypto() {
        assertThat(AssetType.FIAT.isDigitalAsset()).isFalse();
        assertThat(AssetType.STOCK.isDigitalAsset()).isFalse();
        assertThat(AssetType.CRYPTO.isDigitalAsset()).isTrue();
        assertThat(AssetType.POINT.isDigitalAsset()).isFalse();
    }

    @Test
    void isIndivisible_should_return_true_only_for_point() {
        assertThat(AssetType.FIAT.isIndivisible()).isFalse();
        assertThat(AssetType.STOCK.isIndivisible()).isFalse();
        assertThat(AssetType.CRYPTO.isIndivisible()).isFalse();
        assertThat(AssetType.POINT.isIndivisible()).isTrue();
    }
}
