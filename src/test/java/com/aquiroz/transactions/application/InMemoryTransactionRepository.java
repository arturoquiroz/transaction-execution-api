package com.aquiroz.transactions.application;

import com.aquiroz.transactions.domain.model.Transaction;
import com.aquiroz.transactions.domain.model.TransactionStatus;
import com.aquiroz.transactions.domain.port.DuplicateIdempotencyKeyException;
import com.aquiroz.transactions.domain.port.PageQuery;
import com.aquiroz.transactions.domain.port.PageResult;
import com.aquiroz.transactions.domain.port.TransactionFilter;
import com.aquiroz.transactions.domain.port.TransactionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores copies, not references, so a test only sees what the service explicitly saved, like a real database.
 */
class InMemoryTransactionRepository implements TransactionRepository {

    private final Map<UUID, Transaction> rows = new LinkedHashMap<>();
    private Transaction concurrentWinner;
    private boolean sweeperWinsNextUpdate;
    private PageResult<Transaction> searchResult;
    private TransactionFilter lastFilter;
    private PageQuery lastPage;

    void store(Transaction transaction) {
        rows.put(transaction.id(), copyOf(transaction));
    }

    /** The next insert loses the race on the unique key against {@code winner}. */
    void simulateConcurrentInsertOf(Transaction winner) {
        this.concurrentWinner = winner;
    }

    /** The sweeper moves the row to UNKNOWN right before the next conditional update. */
    void simulateSweeperBeforeNextUpdate() {
        this.sweeperWinsNextUpdate = true;
    }

    void willReturnOnSearch(PageResult<Transaction> result) {
        this.searchResult = result;
    }

    int size() {
        return rows.size();
    }

    TransactionFilter lastFilter() {
        return lastFilter;
    }

    PageQuery lastPage() {
        return lastPage;
    }

    @Override
    public void insert(Transaction transaction) {
        if (concurrentWinner != null) {
            store(concurrentWinner);
            concurrentWinner = null;
        }
        if (findByIdempotencyKey(transaction.idempotencyKey()).isPresent()) {
            throw new DuplicateIdempotencyKeyException(transaction.idempotencyKey(), null);
        }
        store(transaction);
    }

    @Override
    public boolean updateFromPending(Transaction transaction) {
        if (sweeperWinsNextUpdate) {
            sweeperWinsNextUpdate = false;
            var stored = rows.get(transaction.id());
            stored.markUnknown("stale pending", fixedSweeperClock());
            store(stored);
        }
        var stored = rows.get(transaction.id());
        if (stored == null || stored.status() != TransactionStatus.PENDING) {
            return false;
        }
        store(transaction);
        return true;
    }

    @Override
    public Optional<Transaction> findById(UUID id) {
        return Optional.ofNullable(rows.get(id)).map(InMemoryTransactionRepository::copyOf);
    }

    @Override
    public Optional<Transaction> findByIdempotencyKey(String idempotencyKey) {
        return rows.values().stream()
                .filter(tx -> tx.idempotencyKey().equals(idempotencyKey))
                .findFirst()
                .map(InMemoryTransactionRepository::copyOf);
    }

    @Override
    public PageResult<Transaction> search(TransactionFilter filter, PageQuery page) {
        this.lastFilter = filter;
        this.lastPage = page;
        return searchResult;
    }

    @Override
    public int markStalePendingAsUnknown(Instant olderThan, Instant now) {
        throw new UnsupportedOperationException("Covered by the sweeper slice");
    }

    private static Clock fixedSweeperClock() {
        return Clock.fixed(Instant.parse("2026-10-23T16:35:00Z"), ZoneOffset.UTC);
    }

    private static Transaction copyOf(Transaction tx) {
        return Transaction.restore(tx.id(), tx.idempotencyKey(), tx.requestHash(), tx.request(), tx.status(),
                tx.providerTransactionId(), tx.balanceAfter(), tx.failureCode(), tx.failureMessage(),
                tx.createdAt(), tx.updatedAt(), tx.executedAt());
    }
}
