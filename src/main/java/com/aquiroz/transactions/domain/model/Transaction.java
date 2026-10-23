package com.aquiroz.transactions.domain.model;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class Transaction {

    public static final String PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE";
    public static final String PROVIDER_OUTCOME_UNKNOWN = "PROVIDER_OUTCOME_UNKNOWN";

    private final UUID id;
    private final String idempotencyKey;
    private final String requestHash;
    private final TransactionRequest request;
    private final Instant createdAt;

    private TransactionStatus status;
    private String providerTransactionId;
    private BigDecimal balanceAfter;
    private String failureCode;
    private String failureMessage;
    private Instant updatedAt;
    private Instant executedAt;

    private Transaction(UUID id, String idempotencyKey, String requestHash, TransactionRequest request,
                        TransactionStatus status, String providerTransactionId, BigDecimal balanceAfter,
                        String failureCode, String failureMessage,
                        Instant createdAt, Instant updatedAt, Instant executedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.requestHash = Objects.requireNonNull(requestHash, "requestHash");
        this.request = Objects.requireNonNull(request, "request");
        this.status = Objects.requireNonNull(status, "status");
        this.providerTransactionId = providerTransactionId;
        this.balanceAfter = balanceAfter;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.executedAt = executedAt;
    }

    public static Transaction pending(TransactionRequest request, String idempotencyKey, String requestHash,
                                      UUID id, Clock clock) {
        var now = clock.instant();
        return new Transaction(id, idempotencyKey, requestHash, request, TransactionStatus.PENDING,
                null, null, null, null, now, now, null);
    }

    public static Transaction restore(UUID id, String idempotencyKey, String requestHash, TransactionRequest request,
                                      TransactionStatus status, String providerTransactionId, BigDecimal balanceAfter,
                                      String failureCode, String failureMessage,
                                      Instant createdAt, Instant updatedAt, Instant executedAt) {
        return new Transaction(id, idempotencyKey, requestHash, request, status, providerTransactionId,
                balanceAfter, failureCode, failureMessage, createdAt, updatedAt, executedAt);
    }

    // Arguments are checked before the transition so a bad call never leaves a half-updated transaction
    public void markExecuted(String providerTransactionId, BigDecimal balanceAfter, Instant executedAt, Clock clock) {
        Objects.requireNonNull(providerTransactionId, "providerTransactionId");
        Objects.requireNonNull(balanceAfter, "balanceAfter");
        Objects.requireNonNull(executedAt, "executedAt");
        moveFromPendingTo(TransactionStatus.EXECUTED, clock);
        this.providerTransactionId = providerTransactionId;
        this.balanceAfter = balanceAfter;
        this.executedAt = executedAt;
    }

    public void markRejected(String code, String message, Clock clock) {
        Objects.requireNonNull(code, "code");
        moveFromPendingTo(TransactionStatus.REJECTED, clock);
        this.failureCode = code;
        this.failureMessage = message;
    }

    public void markFailed(String reason, Clock clock) {
        moveFromPendingTo(TransactionStatus.FAILED, clock);
        this.failureCode = PROVIDER_UNAVAILABLE;
        this.failureMessage = reason;
    }

    public void markUnknown(String reason, Clock clock) {
        moveFromPendingTo(TransactionStatus.UNKNOWN, clock);
        this.failureCode = PROVIDER_OUTCOME_UNKNOWN;
        this.failureMessage = reason;
    }

    private void moveFromPendingTo(TransactionStatus target, Clock clock) {
        if (status != TransactionStatus.PENDING) {
            throw new IllegalStateException("Transaction %s cannot move from %s to %s".formatted(id, status, target));
        }
        this.status = target;
        this.updatedAt = clock.instant();
    }

    public UUID id() {
        return id;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String requestHash() {
        return requestHash;
    }

    public TransactionRequest request() {
        return request;
    }

    public TransactionStatus status() {
        return status;
    }

    public String providerTransactionId() {
        return providerTransactionId;
    }

    public BigDecimal balanceAfter() {
        return balanceAfter;
    }

    public String failureCode() {
        return failureCode;
    }

    public String failureMessage() {
        return failureMessage;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant executedAt() {
        return executedAt;
    }
}
