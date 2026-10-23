package com.aquiroz.transactions.adapter.out.provider;

/**
 * 5xx without a REJECTED body. Thrown, not returned, so the circuit breaker records it as a failure.
 */
class ProviderServerErrorException extends RuntimeException {

    ProviderServerErrorException(int status) {
        super("provider returned " + status);
    }
}
