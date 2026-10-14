package com.aegis.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.aegis.config.AegisProperties;

/**
 * Turns token counts into USD using {@code aegis.pricing}. Versioned model names such as
 * {@code gpt-4o-mini-2024-07-18} fall back to the longest configured prefix.
 */
@Component
public class PricingService {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final Map<String, AegisProperties.ModelPrice> prices;

    public PricingService(AegisProperties properties) {
        this.prices = properties.pricing();
    }

    public BigDecimal cost(String model, long promptTokens, long completionTokens) {
        return price(model)
                .map(price -> price.inputPer1m().multiply(BigDecimal.valueOf(promptTokens))
                        .add(price.outputPer1m().multiply(BigDecimal.valueOf(completionTokens)))
                        .divide(MILLION, 6, RoundingMode.HALF_UP))
                .orElse(BigDecimal.ZERO);
    }

    Optional<AegisProperties.ModelPrice> price(String model) {
        if (model == null) {
            return Optional.empty();
        }
        AegisProperties.ModelPrice exact = prices.get(model);
        if (exact != null) {
            return Optional.of(exact);
        }
        return prices.entrySet().stream()
                .filter(e -> model.startsWith(e.getKey()))
                .max(Comparator.comparingInt(e -> e.getKey().length()))
                .map(Map.Entry::getValue);
    }
}
