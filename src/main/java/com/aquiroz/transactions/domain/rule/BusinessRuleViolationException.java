package com.aquiroz.transactions.domain.rule;

public class BusinessRuleViolationException extends RuntimeException {

    private final RuleViolation violation;

    public BusinessRuleViolationException(RuleViolation violation) {
        super(violation.message());
        this.violation = violation;
    }

    public RuleViolation violation() {
        return violation;
    }
}
