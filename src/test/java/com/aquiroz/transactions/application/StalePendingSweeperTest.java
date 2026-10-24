package com.aquiroz.transactions.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aquiroz.transactions.domain.model.Transaction;
import com.aquiroz.transactions.domain.model.TransactionRequest;
import com.aquiroz.transactions.domain.model.TransactionStatus;
import com.aquiroz.transactions.domain.model.TransactionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StalePendingSweeperTest {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    private final InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
    private final StalePendingSweeper sweeper =
            new StalePendingSweeper(repository, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));

    @Test
    void shouldAskRepositoryForPendingOlderThanStaleAfter() {
        sweeper.sweep();

        assertThat(repository.lastSweepOlderThan()).isEqualTo(NOW.minus(Duration.ofMinutes(5)));
        assertThat(repository.lastSweepNow()).isEqualTo(NOW);
    }

    @Test
    void shouldMoveOnlyPendingOlderThanStaleAfter() {
        var stale = pendingCreatedAt(NOW.minus(Duration.ofMinutes(6)));
        var recent = pendingCreatedAt(NOW.minus(Duration.ofMinutes(4)));
        repository.store(stale);
        repository.store(recent);

        int moved = sweeper.sweep();

        assertThat(moved).isEqualTo(1);
        assertThat(repository.findById(stale.id()).orElseThrow().status()).isEqualTo(TransactionStatus.UNKNOWN);
        assertThat(repository.findById(recent.id()).orElseThrow().status()).isEqualTo(TransactionStatus.PENDING);
    }

    private static Transaction pendingCreatedAt(Instant createdAt) {
        var request = new TransactionRequest("acc-1", TransactionType.DEBIT, new BigDecimal("10.00"), "MXN", null);
        return Transaction.pending(request, UUID.randomUUID().toString(), "0".repeat(64), UUID.randomUUID(),
                Clock.fixed(createdAt, ZoneOffset.UTC));
    }
}
