package com.aquiroz.transactions.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aquiroz.transactions.IntegrationTest;
import com.aquiroz.transactions.domain.model.Transaction;
import com.aquiroz.transactions.domain.model.TransactionRequest;
import com.aquiroz.transactions.domain.model.TransactionStatus;
import com.aquiroz.transactions.domain.model.TransactionType;
import com.aquiroz.transactions.domain.port.TransactionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The real scheduled job, not a direct call: an orphan PENDING must move to UNKNOWN on its own (Slice 6).
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE, properties = {
        "transactions.sweeper.enabled=true",
        "transactions.sweeper.interval=200ms",
        "transactions.sweeper.stale-after=5m"})
@IntegrationTest
class StalePendingSweeperIntegrationTest {

    @Autowired
    private TransactionRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void shouldMoveOrphanPendingToUnknownOnItsOwn() {
        var orphan = pendingCreatedAt(Instant.now().minus(Duration.ofMinutes(10)));
        var recent = pendingCreatedAt(Instant.now());
        repository.insert(orphan);
        repository.insert(recent);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(repository.findById(orphan.id()).orElseThrow().status())
                        .isEqualTo(TransactionStatus.UNKNOWN));

        var swept = repository.findById(orphan.id()).orElseThrow();
        assertThat(swept.failureCode()).isEqualTo(Transaction.STALE_PENDING);
        assertThat(repository.findById(recent.id()).orElseThrow().status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(jdbc.sql("SELECT changed_by FROM transaction_events WHERE transaction_id = :id AND to_status = 'UNKNOWN'")
                .param("id", orphan.id())
                .query(String.class)
                .single()).isEqualTo("SWEEPER");
    }

    private static Transaction pendingCreatedAt(Instant createdAt) {
        var request = new TransactionRequest("acc-orphan", TransactionType.DEBIT, new BigDecimal("10.00"), "MXN", null);
        return Transaction.pending(request, UUID.randomUUID().toString(), "0".repeat(64), UUID.randomUUID(),
                Clock.fixed(createdAt, ZoneOffset.UTC));
    }
}
