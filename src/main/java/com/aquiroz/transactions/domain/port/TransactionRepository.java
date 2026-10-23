package com.aquiroz.transactions.domain.port;

import com.aquiroz.transactions.domain.model.Transaction;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence of the transaction aggregate. Each method is its own short database transaction.
 */
public interface TransactionRepository {

    /**
     * @throws DuplicateIdempotencyKeyException if another transaction already holds the same idempotency key
     */
    void insert(Transaction transaction);

    /**
     * Persists the final state only if the stored row is still PENDING.
     *
     * @return false when the row had already left PENDING (e.g. the stale-pending sweeper won the race)
     */
    boolean updateFromPending(Transaction transaction);

    Optional<Transaction> findById(UUID id);

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    PageResult<Transaction> search(TransactionFilter filter, PageQuery page);

    /**
     * @return how many PENDING transactions created before {@code olderThan} were moved to UNKNOWN
     */
    int markStalePendingAsUnknown(Instant olderThan, Instant now);
}
