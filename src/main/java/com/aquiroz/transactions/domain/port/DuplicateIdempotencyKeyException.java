package com.aquiroz.transactions.domain.port;

public class DuplicateIdempotencyKeyException extends RuntimeException {

    public DuplicateIdempotencyKeyException(String idempotencyKey, Throwable cause) {
        super("A transaction with idempotency key " + idempotencyKey + " already exists", cause);
    }
}
