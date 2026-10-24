package com.aquiroz.transactions.domain.port;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Classified result of a provider call. NotExecuted means the money surely did not move (FAILED);
 * Uncertain means it may have moved (UNKNOWN).
 */
public sealed interface ProviderOutcome {

    record Approved(String providerTransactionId, BigDecimal balance, Instant executedAt) implements ProviderOutcome {
    }

    record Rejected(String code, String message) implements ProviderOutcome {
    }

    record NotExecuted(String reason) implements ProviderOutcome {
    }

    record Uncertain(String reason) implements ProviderOutcome {
    }
}
