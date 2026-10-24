package com.aquiroz.transactions.application;

import com.aquiroz.transactions.domain.model.Transaction;

/**
 * @param replayed true when the transaction already existed for the idempotency key and was not executed again
 */
public record ExecutionResult(Transaction transaction, boolean replayed) {
}
