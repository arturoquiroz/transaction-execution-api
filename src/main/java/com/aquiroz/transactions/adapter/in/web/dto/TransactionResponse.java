package com.aquiroz.transactions.adapter.in.web.dto;

import com.aquiroz.transactions.domain.model.Transaction;
import com.aquiroz.transactions.domain.model.TransactionStatus;
import com.aquiroz.transactions.domain.model.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        String description,
        TransactionStatus status,
        String providerTransactionId,
        BigDecimal balanceAfter,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        Instant executedAt) {

    public static TransactionResponse from(Transaction tx) {
        var request = tx.request();
        return new TransactionResponse(tx.id(), request.accountId(), request.type(), request.amount(),
                request.currency(), request.description(), tx.status(), tx.providerTransactionId(),
                tx.balanceAfter(), tx.failureCode(), tx.failureMessage(), tx.createdAt(), tx.executedAt());
    }
}
