package com.aquiroz.transactions.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.aquiroz.transactions.IntegrationTest;
import com.aquiroz.transactions.domain.model.Transaction;
import com.aquiroz.transactions.domain.model.TransactionRequest;
import com.aquiroz.transactions.domain.model.TransactionStatus;
import com.aquiroz.transactions.domain.model.TransactionType;
import com.aquiroz.transactions.domain.port.DuplicateIdempotencyKeyException;
import com.aquiroz.transactions.domain.port.PageQuery;
import com.aquiroz.transactions.domain.port.TransactionFilter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@IntegrationTest
class JdbcTransactionRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-10-23T16:30:00Z");

    @Autowired
    private JdbcTransactionRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void cleanTable() {
        jdbc.sql("DELETE FROM transaction_events").update();
        jdbc.sql("DELETE FROM transactions").update();
    }

    @Test
    void shouldRoundTripEveryFieldOfAnExecutedTransaction() {
        var tx = pending("key-1", "acc-1", TransactionType.DEBIT, T0);
        tx.markExecuted("txn-789", new BigDecimal("5500.00"), T0.plusSeconds(1), clockAt(T0.plusSeconds(2)));
        repository.insert(tx);

        var stored = repository.findById(tx.id()).orElseThrow();

        assertThat(stored).usingRecursiveComparison().isEqualTo(tx);
        assertThat(stored.request().amount()).isEqualTo(new BigDecimal("1500.00"));
    }

    @Test
    void shouldFindByIdempotencyKey() {
        var tx = pending("key-1", "acc-1", TransactionType.CREDIT, T0);
        repository.insert(tx);

        assertThat(repository.findByIdempotencyKey("key-1")).hasValueSatisfying(
                found -> assertThat(found.id()).isEqualTo(tx.id()));
        assertThat(repository.findByIdempotencyKey("missing")).isEmpty();
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void shouldRejectSecondTransactionWithSameIdempotencyKey() {
        repository.insert(pending("key-1", "acc-1", TransactionType.CREDIT, T0));

        assertThatExceptionOfType(DuplicateIdempotencyKeyException.class)
                .isThrownBy(() -> repository.insert(pending("key-1", "acc-2", TransactionType.DEBIT, T0)));
    }

    @Test
    void shouldUpdateFinalStateWhileStillPending() {
        var tx = pending("key-1", "acc-1", TransactionType.DEBIT, T0);
        repository.insert(tx);
        tx.markRejected("INSUFFICIENT_FUNDS", "Not enough balance", clockAt(T0.plusSeconds(1)));

        boolean updated = repository.updateFromPending(tx);

        assertThat(updated).isTrue();
        var stored = repository.findById(tx.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(stored.failureCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(stored.updatedAt()).isEqualTo(T0.plusSeconds(1));
    }

    @Test
    void shouldNotOverwriteRowThatAlreadyLeftPending() {
        var tx = pending("key-1", "acc-1", TransactionType.DEBIT, T0);
        repository.insert(tx);
        repository.markStalePendingAsUnknown(T0.plusSeconds(1), T0.plusSeconds(300));
        tx.markExecuted("txn-789", new BigDecimal("5500.00"), T0.plusSeconds(301), clockAt(T0.plusSeconds(301)));

        boolean updated = repository.updateFromPending(tx);

        assertThat(updated).isFalse();
        assertThat(repository.findById(tx.id()).orElseThrow().status()).isEqualTo(TransactionStatus.UNKNOWN);
    }

    @Test
    void shouldMoveOnlyStalePendingTransactionsToUnknown() {
        var stale = pending("key-stale", "acc-1", TransactionType.DEBIT, T0);
        var recent = pending("key-recent", "acc-1", TransactionType.DEBIT, T0.plusSeconds(600));
        var finished = pending("key-finished", "acc-1", TransactionType.DEBIT, T0);
        finished.markFailed("circuit open", clockAt(T0));
        repository.insert(stale);
        repository.insert(recent);
        repository.insert(finished);

        int moved = repository.markStalePendingAsUnknown(T0.plusSeconds(300), T0.plusSeconds(900));

        assertThat(moved).isEqualTo(1);
        var swept = repository.findById(stale.id()).orElseThrow();
        assertThat(swept.status()).isEqualTo(TransactionStatus.UNKNOWN);
        assertThat(swept.failureCode()).isEqualTo(Transaction.STALE_PENDING);
        assertThat(swept.updatedAt()).isEqualTo(T0.plusSeconds(900));
        assertThat(repository.findById(recent.id()).orElseThrow().status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(repository.findById(finished.id()).orElseThrow().status()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void shouldSearchNewestFirstAndReportNextPage() {
        for (int i = 0; i < 5; i++) {
            repository.insert(pending("key-" + i, "acc-1", TransactionType.CREDIT, T0.plusSeconds(i)));
        }

        var first = repository.search(noFilter(), new PageQuery(0, 2));
        var last = repository.search(noFilter(), new PageQuery(2, 2));

        assertThat(first.items()).extracting(Transaction::idempotencyKey).containsExactly("key-4", "key-3");
        assertThat(first.hasNext()).isTrue();
        assertThat(last.items()).extracting(Transaction::idempotencyKey).containsExactly("key-0");
        assertThat(last.hasNext()).isFalse();
    }

    @Test
    void shouldReportNoNextPageWhenRowsExactlyFillThePage() {
        repository.insert(pending("key-0", "acc-1", TransactionType.CREDIT, T0));
        repository.insert(pending("key-1", "acc-1", TransactionType.CREDIT, T0.plusSeconds(1)));

        var page = repository.search(noFilter(), new PageQuery(0, 2));

        assertThat(page.items()).hasSize(2);
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void shouldCombineAccountStatusAndTypeFilters() {
        repository.insert(pending("key-1", "acc-1", TransactionType.DEBIT, T0));
        repository.insert(pending("key-2", "acc-1", TransactionType.CREDIT, T0));
        repository.insert(pending("key-3", "acc-2", TransactionType.DEBIT, T0));
        var failed = pending("key-4", "acc-1", TransactionType.DEBIT, T0);
        failed.markFailed("circuit open", clockAt(T0));
        repository.insert(failed);

        var byAccount = repository.search(new TransactionFilter("acc-1", null, null), new PageQuery(0, 10));
        var byAll = repository.search(
                new TransactionFilter("acc-1", TransactionStatus.PENDING, TransactionType.DEBIT), new PageQuery(0, 10));

        assertThat(byAccount.items()).extracting(Transaction::idempotencyKey)
                .containsExactlyInAnyOrder("key-1", "key-2", "key-4");
        assertThat(byAll.items()).extracting(Transaction::idempotencyKey).containsExactly("key-1");
    }

    @Test
    void shouldEnforceCheckConstraintsOnStatus() {
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> jdbc.sql("""
                        INSERT INTO transactions (id, idempotency_key, request_hash, account_id, type, amount, currency,
                                                  status, created_at, updated_at)
                        VALUES (:id, 'key-x', :hash, 'acc-1', 'DEBIT', 10.00, 'MXN', 'APPROVED', now(), now())
                        """)
                .param("id", UUID.randomUUID())
                .param("hash", "0".repeat(64))
                .update());
    }

    @Test
    void shouldRecordCreationEventOnInsert() {
        var tx = pending("key-1", "acc-1", TransactionType.DEBIT, T0);

        repository.insert(tx);

        assertThat(eventsOf(tx.id())).containsExactly(new Event(null, "PENDING", null, "API", T0));
    }

    @Test
    void shouldRecordTransitionEventWhenFinalStateIsSaved() {
        var tx = pending("key-1", "acc-1", TransactionType.DEBIT, T0);
        repository.insert(tx);
        tx.markRejected("INSUFFICIENT_FUNDS", "Not enough balance", clockAt(T0.plusSeconds(1)));

        repository.updateFromPending(tx);

        assertThat(eventsOf(tx.id())).containsExactly(
                new Event(null, "PENDING", null, "API", T0),
                new Event("PENDING", "REJECTED", "INSUFFICIENT_FUNDS", "API", T0.plusSeconds(1)));
    }

    @Test
    void shouldRecordSweeperEventAndNoEventForTheLosingUpdate() {
        var tx = pending("key-1", "acc-1", TransactionType.DEBIT, T0);
        repository.insert(tx);
        repository.markStalePendingAsUnknown(T0.plusSeconds(1), T0.plusSeconds(300));
        tx.markExecuted("txn-789", new BigDecimal("5500.00"), T0.plusSeconds(301), clockAt(T0.plusSeconds(301)));

        repository.updateFromPending(tx);

        assertThat(eventsOf(tx.id())).containsExactly(
                new Event(null, "PENDING", null, "API", T0),
                new Event("PENDING", "UNKNOWN", Transaction.STALE_PENDING, "SWEEPER", T0.plusSeconds(300)));
    }

    @Test
    void shouldLeaveNoEventWhenInsertIsRejectedAsDuplicate() {
        var original = pending("key-1", "acc-1", TransactionType.CREDIT, T0);
        var duplicate = pending("key-1", "acc-2", TransactionType.DEBIT, T0);
        repository.insert(original);

        assertThatExceptionOfType(DuplicateIdempotencyKeyException.class).isThrownBy(() -> repository.insert(duplicate));

        assertThat(eventsOf(duplicate.id())).isEmpty();
        assertThat(eventsOf(original.id())).hasSize(1);
    }

    private record Event(String fromStatus, String toStatus, String failureCode, String changedBy,
                         Instant occurredAt) {
    }

    private List<Event> eventsOf(UUID transactionId) {
        return jdbc.sql("""
                        SELECT from_status, to_status, failure_code, changed_by, occurred_at
                          FROM transaction_events WHERE transaction_id = :id ORDER BY id
                        """)
                .param("id", transactionId)
                .query((rs, _) -> new Event(rs.getString("from_status"), rs.getString("to_status"),
                        rs.getString("failure_code"), rs.getString("changed_by"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private static Transaction pending(String key, String accountId, TransactionType type, Instant createdAt) {
        var request = new TransactionRequest(accountId, type, new BigDecimal("1500.00"), "MXN", "rent");
        return Transaction.pending(request, key, "0".repeat(64), UUID.randomUUID(), clockAt(createdAt));
    }

    private static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static TransactionFilter noFilter() {
        return new TransactionFilter(null, null, null);
    }
}
