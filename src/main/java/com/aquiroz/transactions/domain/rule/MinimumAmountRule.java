package com.aquiroz.transactions.domain.rule;

import com.aquiroz.transactions.domain.model.TransactionRequest;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

public final class MinimumAmountRule implements TransactionRule {

    public static final String CODE = "AMOUNT_BELOW_MINIMUM";

    private final BigDecimal minimum;

    public MinimumAmountRule(BigDecimal minimum) {
        this.minimum = Objects.requireNonNull(minimum, "minimum");
    }

    @Override
    public Optional<RuleViolation> evaluate(TransactionRequest request) {
        if (request.amount().compareTo(minimum) <= 0) {
            return Optional.of(new RuleViolation(CODE, "Amount must be greater than " + minimum.toPlainString()));
        }
        return Optional.empty();
    }
}
