package com.aquiroz.transactions.domain.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.aquiroz.transactions.domain.model.TransactionRequest;
import com.aquiroz.transactions.domain.model.TransactionType;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SupportedCurrencyRuleTest {

    private final SupportedCurrencyRule rule = new SupportedCurrencyRule(Set.of("MXN"));

    @Test
    void shouldAcceptSupportedCurrency() {
        assertThat(rule.evaluate(request("MXN"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "mxn", "Mxn"})
    void shouldRejectUnsupportedCurrencyUsingExactMatch(String currency) {
        var violation = rule.evaluate(request(currency));

        assertThat(violation).hasValueSatisfying(v -> {
            assertThat(v.code()).isEqualTo(SupportedCurrencyRule.CODE);
            assertThat(v.message()).isEqualTo("Currency " + currency + " is not supported");
        });
    }

    private static TransactionRequest request(String currency) {
        return new TransactionRequest("acc-1", TransactionType.CREDIT, new BigDecimal("10.00"), currency, null);
    }
}
