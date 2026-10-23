package com.aquiroz.transactions.application;

import com.aquiroz.transactions.domain.model.TransactionRequest;
import java.util.Objects;

public record ExecuteTransactionCommand(TransactionRequest request, String idempotencyKey) {

    public ExecuteTransactionCommand {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
    }
}
