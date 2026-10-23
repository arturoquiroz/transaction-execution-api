package com.aquiroz.transactions.domain.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.aquiroz.transactions.domain.model.TransactionRequest;
import com.aquiroz.transactions.domain.model.TransactionType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TransactionPolicyTest {

    private final TransactionPolicy policy = new TransactionPolicy(List.of(
            new MinimumAmountRule(new BigDecimal("1.00")),
            new DebitLimitRule(new BigDecimal("10000.00")),
            new SupportedCurrencyRule(Set.of("MXN"))));

    @Test
    void shouldAllowRequestThatSatisfiesEveryRule() {
        var request = new TransactionRequest("acc-1", TransactionType.DEBIT, new BigDecimal("10000.00"), "MXN", null);

        assertThatCode(() -> policy.enforce(request)).doesNotThrowAnyException();
    }

    @Test
    void shouldReportOnlyFirstViolationInRuleOrder() {
        var request = new TransactionRequest("acc-1", TransactionType.DEBIT, new BigDecimal("10000.01"), "USD", null);

        assertThatExceptionOfType(BusinessRuleViolationException.class)
                .isThrownBy(() -> policy.enforce(request))
                .satisfies(e -> assertThat(e.violation().code()).isEqualTo(DebitLimitRule.CODE))
                .withMessage("DEBIT amount must not exceed 10000.00");
    }

    @Test
    void shouldNotEvaluateRulesAfterFirstViolation() {
        var laterRuleEvaluated = new AtomicBoolean(false);
        TransactionRule laterRule = _ -> {
            laterRuleEvaluated.set(true);
            return Optional.empty();
        };
        var failFastPolicy = new TransactionPolicy(List.of(new MinimumAmountRule(new BigDecimal("1.00")), laterRule));
        var request = new TransactionRequest("acc-1", TransactionType.CREDIT, new BigDecimal("1.00"), "MXN", null);

        assertThatExceptionOfType(BusinessRuleViolationException.class)
                .isThrownBy(() -> failFastPolicy.enforce(request));
        assertThat(laterRuleEvaluated).isFalse();
    }
}
