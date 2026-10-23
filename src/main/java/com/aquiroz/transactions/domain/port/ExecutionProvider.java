package com.aquiroz.transactions.domain.port;

import com.aquiroz.transactions.domain.model.TransactionRequest;

/**
 * Executes a transaction against the external provider. Expected failures (timeouts, 5xx, open circuit)
 * are returned as a {@link ProviderOutcome}, never thrown. See LLD §5.3.
 */
public interface ExecutionProvider {

    ProviderOutcome execute(TransactionRequest request);
}
