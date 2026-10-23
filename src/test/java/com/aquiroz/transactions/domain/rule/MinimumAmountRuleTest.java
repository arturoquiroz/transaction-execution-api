package com.aquiroz.transactions.domain.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.aquiroz.transactions.domain.model.TransactionRequest;
import com.aquiroz.transactions.domain.model.TransactionType;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class MinimumAmountRuleTest {

    private final MinimumAmountRule rule = new MinimumAmountRule(new BigDecimal("1.00"));

    @ParameterizedTest
    @ValueSource(strings = {"1.00", "0.99", "0.00", "-5.00"})
    void shouldRejectAmountAtOrBelowMinimum(String amount) {
        var violation = rule.evaluate(request(TransactionType.CREDIT, amount));

        assertThat(violation).hasValueSatisfying(v -> {
            assertThat(v.code()).isEqualTo(MinimumAmountRule.CODE);
            assertThat(v.message()).isEqualTo("Amount must be greater than 1.00");
        });
    }

    @ParameterizedTest
    @EnumSource(TransactionType.class)
    void shouldAcceptAmountJustAboveMinimumForAnyType(TransactionType type) {
        assertThat(rule.evaluate(request(type, "1.01"))).isEmpty();
    }

    private static TransactionRequest request(TransactionType type, String amount) {
        return new TransactionRequest("acc-1", type, new BigDecimal(amount), "MXN", null);
    }
}
