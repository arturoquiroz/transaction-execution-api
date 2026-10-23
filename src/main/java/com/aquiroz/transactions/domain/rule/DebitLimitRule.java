package com.aquiroz.transactions.domain.rule;

import com.aquiroz.transactions.domain.model.TransactionRequest;
import com.aquiroz.transactions.domain.model.TransactionType;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

public final class DebitLimitRule implements TransactionRule {

    public static final String CODE = "DEBIT_LIMIT_EXCEEDED";

    private final BigDecimal maximum;

    public DebitLimitRule(BigDecimal maximum) {
        this.maximum = Objects.requireNonNull(maximum, "maximum");
    }

    @Override
    public Optional<RuleViolation> evaluate(TransactionRequest request) {
        if (request.type() == TransactionType.DEBIT && request.amount().compareTo(maximum) > 0) {
            return Optional.of(new RuleViolation(CODE, "DEBIT amount must not exceed " + maximum.toPlainString()));
        }
        return Optional.empty();
    }
}
