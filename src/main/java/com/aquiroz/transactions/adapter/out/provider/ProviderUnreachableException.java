package com.aquiroz.transactions.adapter.out.provider;

/**
 * The request never reached the provider (connection refused or connect timeout): the only failure safe to retry.
 */
class ProviderUnreachableException extends RuntimeException {

    ProviderUnreachableException(Throwable cause) {
        super("Provider unreachable", cause);
    }
}
