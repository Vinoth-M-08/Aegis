package com.aegis.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aegis.TestProperties;
import com.aegis.config.AegisProperties;

class PricingServiceTest {

    private final PricingService pricing = new PricingService(TestProperties.with(Map.of(
            "gpt-4o", new AegisProperties.ModelPrice(new BigDecimal("2.50"), new BigDecimal("10.00")),
            "gpt-4o-mini", new AegisProperties.ModelPrice(new BigDecimal("0.15"), new BigDecimal("0.60")))));

    @Test
    void pricesPerMillionTokens() {
        assertThat(pricing.cost("gpt-4o", 1_000_000, 500_000)).isEqualByComparingTo("7.50");
    }

    @Test
    void versionedModelUsesLongestMatchingPrefix() {
        assertThat(pricing.cost("gpt-4o-mini-2024-07-18", 1_000, 1_000)).isEqualByComparingTo("0.00075");
    }

    @Test
    void unknownModelCostsNothing() {
        assertThat(pricing.cost("llama3", 1_000, 1_000)).isEqualByComparingTo("0");
    }
}
