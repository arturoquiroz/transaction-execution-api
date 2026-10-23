package com.aquiroz.transactions.domain.rule;

import com.aquiroz.transactions.domain.model.TransactionRequest;
import java.util.Optional;

public interface TransactionRule {

    Optional<RuleViolation> evaluate(TransactionRequest request);
}
