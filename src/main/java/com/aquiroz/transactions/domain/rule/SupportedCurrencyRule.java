package com.aquiroz.transactions.domain.rule;

import com.aquiroz.transactions.domain.model.TransactionRequest;
import java.util.Optional;
import java.util.Set;

public final class SupportedCurrencyRule implements TransactionRule {

    public static final String CODE = "UNSUPPORTED_CURRENCY";

    private final Set<String> supportedCurrencies;

    public SupportedCurrencyRule(Set<String> supportedCurrencies) {
        this.supportedCurrencies = Set.copyOf(supportedCurrencies);
    }

    @Override
    public Optional<RuleViolation> evaluate(TransactionRequest request) {
        if (!supportedCurrencies.contains(request.currency())) {
            return Optional.of(new RuleViolation(CODE, "Currency " + request.currency() + " is not supported"));
        }
        return Optional.empty();
    }
}
