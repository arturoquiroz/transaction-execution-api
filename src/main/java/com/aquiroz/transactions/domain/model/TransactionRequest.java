package com.aquiroz.transactions.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record TransactionRequest(String accountId, TransactionType type, BigDecimal amount,
                                 String currency, String description) {

    public TransactionRequest {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        amount = toMoneyScale(amount);
    }

    private static BigDecimal toMoneyScale(BigDecimal amount) {
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("amount must have at most 2 decimal places: " + amount, e);
        }
    }
}
