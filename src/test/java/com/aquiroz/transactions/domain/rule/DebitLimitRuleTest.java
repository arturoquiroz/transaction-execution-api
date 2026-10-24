package com.aquiroz.transactions.domain.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.aquiroz.transactions.domain.model.TransactionRequest;
import com.aquiroz.transactions.domain.model.TransactionType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DebitLimitRuleTest {

    private final DebitLimitRule rule = new DebitLimitRule(new BigDecimal("10000.00"));

    @Test
    void shouldAcceptDebitExactlyAtLimit() {
        assertThat(rule.evaluate(request(TransactionType.DEBIT, "10000.00"))).isEmpty();
    }

    @Test
    void shouldRejectDebitAboveLimit() {
        var violation = rule.evaluate(request(TransactionType.DEBIT, "10000.01"));

        assertThat(violation).hasValueSatisfying(v -> {
            assertThat(v.code()).isEqualTo(DebitLimitRule.CODE);
            assertThat(v.message()).isEqualTo("DEBIT amount must not exceed 10000.00");
        });
    }

    @Test
    void shouldNotLimitCreditAboveDebitLimit() {
        assertThat(rule.evaluate(request(TransactionType.CREDIT, "1000000.00"))).isEmpty();
    }

    private static TransactionRequest request(TransactionType type, String amount) {
        return new TransactionRequest("acc-1", type, new BigDecimal(amount), "MXN", null);
    }
}
