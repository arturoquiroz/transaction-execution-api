package com.aquiroz.transactions.domain.port;

import java.util.UUID;

/**
 * Supplies the identifier of each new transaction. Implementations must return time-ordered ids.
 */
public interface TransactionIdGenerator {

    UUID nextId();
}
