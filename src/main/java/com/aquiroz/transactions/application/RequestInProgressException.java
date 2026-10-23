package com.aquiroz.transactions.application;

public class RequestInProgressException extends RuntimeException {

    public RequestInProgressException(String idempotencyKey) {
        super("A request with idempotency key " + idempotencyKey + " is still in progress");
    }
}
