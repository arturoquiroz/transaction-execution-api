package com.aquiroz.transactions.domain.port;

import com.aquiroz.transactions.domain.model.TransactionStatus;
import com.aquiroz.transactions.domain.model.TransactionType;

/**
 * Optional search criteria; a null field means "do not filter by it".
 */
public record TransactionFilter(String accountId, TransactionStatus status, TransactionType type) {
}
