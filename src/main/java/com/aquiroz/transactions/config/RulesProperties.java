package com.aquiroz.transactions.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Business rule limits from {@code transactions.rules}. Invalid limits fail the startup instead of a request.
 */
@ConfigurationProperties("transactions.rules")
public record RulesProperties(BigDecimal minimumAmount, BigDecimal debitMaxAmount, Set<String> supportedCurrencies) {

    public RulesProperties {
        minimumAmount = toMoneyScale(Objects.requireNonNull(minimumAmount, "minimum-amount"), "minimum-amount");
        debitMaxAmount = toMoneyScale(Objects.requireNonNull(debitMaxAmount, "debit-max-amount"), "debit-max-amount");
        if (minimumAmount.compareTo(debitMaxAmount) >= 0) {
            throw new IllegalArgumentException("minimum-amount (%s) must be lower than debit-max-amount (%s)"
                    .formatted(minimumAmount, debitMaxAmount));
        }
        Objects.requireNonNull(supportedCurrencies, "supported-currencies");
        if (supportedCurrencies.isEmpty()) {
            throw new IllegalArgumentException("supported-currencies must not be empty");
        }
        supportedCurrencies = Set.copyOf(supportedCurrencies);
    }

    private static BigDecimal toMoneyScale(BigDecimal value, String property) {
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(property + " must have at most 2 decimal places: " + value, e);
        }
    }
}
