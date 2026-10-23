package com.aquiroz.transactions.domain.rule;

import com.aquiroz.transactions.domain.model.TransactionRequest;
import java.util.List;

public final class TransactionPolicy {

    private final List<TransactionRule> rules;

    public TransactionPolicy(List<TransactionRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public void enforce(TransactionRequest request) {
        for (var rule : rules) {
            var violation = rule.evaluate(request);
            if (violation.isPresent()) {
                throw new BusinessRuleViolationException(violation.get());
            }
        }
    }
}
