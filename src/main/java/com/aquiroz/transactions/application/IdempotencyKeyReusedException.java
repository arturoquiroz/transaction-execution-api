package com.aquiroz.transactions.application;

public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String idempotencyKey) {
        super("Idempotency key " + idempotencyKey + " was already used with a different payload");
    }
}
