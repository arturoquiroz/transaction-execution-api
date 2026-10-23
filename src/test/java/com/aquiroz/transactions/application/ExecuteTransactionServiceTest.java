package com.aquiroz.transactions.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.aquiroz.transactions.domain.model.Transaction;
import com.aquiroz.transactions.domain.model.TransactionRequest;
import com.aquiroz.transactions.domain.model.TransactionStatus;
import com.aquiroz.transactions.domain.model.TransactionType;
import com.aquiroz.transactions.domain.port.ProviderOutcome.Approved;
import com.aquiroz.transactions.domain.port.ProviderOutcome.NotExecuted;
import com.aquiroz.transactions.domain.port.ProviderOutcome.Rejected;
import com.aquiroz.transactions.domain.port.ProviderOutcome.Uncertain;
import com.aquiroz.transactions.domain.rule.BusinessRuleViolationException;
import com.aquiroz.transactions.domain.rule.DebitLimitRule;
import com.aquiroz.transactions.domain.rule.MinimumAmountRule;
import com.aquiroz.transactions.domain.rule.SupportedCurrencyRule;
import com.aquiroz.transactions.domain.rule.TransactionPolicy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExecuteTransactionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-23T16:30:00Z");
    private static final Instant PROVIDER_EXECUTED_AT = Instant.parse("2026-10-23T16:30:01Z");
    private static final UUID ID = UUID.fromString("0192f3b4-0000-7000-8000-000000000001");
    private static final String KEY = "key-1";

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
    private final FakeExecutionProvider provider = new FakeExecutionProvider();
    private final TransactionPolicy policy = new TransactionPolicy(List.of(
            new MinimumAmountRule(new BigDecimal("1.00")),
            new DebitLimitRule(new BigDecimal("10000.00")),
            new SupportedCurrencyRule(Set.of("MXN"))));
    private final ExecuteTransactionService service =
            new ExecuteTransactionService(repository, provider, policy, () -> ID, clock);

    @Test
    void shouldMarkExecutedWhenProviderApproves() {
        provider.willReturn(new Approved("txn-789", new BigDecimal("5500.00"), PROVIDER_EXECUTED_AT));

        var result = service.execute(command(debit("1500.00")));

        assertThat(result.replayed()).isFalse();
        assertThat(result.transaction().id()).isEqualTo(ID);
        assertThat(result.transaction().createdAt()).isEqualTo(NOW);
        assertThat(storedStatus()).isEqualTo(TransactionStatus.EXECUTED);
        assertThat(repository.findById(ID).orElseThrow().providerTransactionId()).isEqualTo("txn-789");
    }

    @Test
    void shouldMarkRejectedWhenProviderRejects() {
        provider.willReturn(new Rejected("INSUFFICIENT_FUNDS", "Not enough balance"));

        var result = service.execute(command(debit("1500.00")));

        assertThat(result.transaction().status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(storedStatus()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(repository.findById(ID).orElseThrow().failureCode()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void shouldMarkFailedWhenProviderDidNotExecute() {
        provider.willReturn(new NotExecuted("circuit open"));

        var result = service.execute(command(debit("1500.00")));

        assertThat(result.transaction().status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(storedStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void shouldMarkUnknownWhenProviderOutcomeIsUncertain() {
        provider.willReturn(new Uncertain("provider read timeout"));

        var result = service.execute(command(debit("1500.00")));

        assertThat(result.transaction().status()).isEqualTo(TransactionStatus.UNKNOWN);
        assertThat(storedStatus()).isEqualTo(TransactionStatus.UNKNOWN);
    }

    @Test
    void shouldPersistPendingBeforeCallingProvider() {
        provider.willReturn(new Approved("txn-789", new BigDecimal("5500.00"), PROVIDER_EXECUTED_AT));
        provider.onCall(_ -> assertThat(storedStatus()).isEqualTo(TransactionStatus.PENDING));

        service.execute(command(debit("1500.00")));

        assertThat(provider.calls()).isEqualTo(1);
    }

    @Test
    void shouldNeitherPersistNorCallProviderWhenRuleIsViolated() {
        assertThatExceptionOfType(BusinessRuleViolationException.class)
                .isThrownBy(() -> service.execute(command(debit("10000.01"))));

        assertThat(repository.size()).isZero();
        assertThat(provider.calls()).isZero();
    }

    @Test
    void shouldReplayFinishedTransactionWithoutCallingProviderAgain() {
        provider.willReturn(new Approved("txn-789", new BigDecimal("5500.00"), PROVIDER_EXECUTED_AT));
        service.execute(command(debit("1500.00")));

        var replay = service.execute(command(debit("1500.00")));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.transaction().status()).isEqualTo(TransactionStatus.EXECUTED);
        assertThat(provider.calls()).isEqualTo(1);
    }

    @Test
    void shouldTreatAmountsWithDifferentScaleAsSamePayload() {
        provider.willReturn(new Approved("txn-789", new BigDecimal("5500.00"), PROVIDER_EXECUTED_AT));
        service.execute(command(debit("100")));

        var replay = service.execute(command(debit("100.00")));

        assertThat(replay.replayed()).isTrue();
    }

    @Test
    void shouldRejectReusedKeyWithDifferentPayload() {
        provider.willReturn(new Approved("txn-789", new BigDecimal("5500.00"), PROVIDER_EXECUTED_AT));
        service.execute(command(debit("1500.00")));

        assertThatExceptionOfType(IdempotencyKeyReusedException.class)
                .isThrownBy(() -> service.execute(command(debit("1600.00"))));
        assertThat(provider.calls()).isEqualTo(1);
    }

    @Test
    void shouldReportInProgressWhenOriginalIsStillPending() {
        repository.store(pendingFor(debit("1500.00")));

        assertThatExceptionOfType(RequestInProgressException.class)
                .isThrownBy(() -> service.execute(command(debit("1500.00"))));
        assertThat(provider.calls()).isZero();
    }

    @Test
    void shouldReportInProgressWhenConcurrentRequestWinsTheInsert() {
        repository.simulateConcurrentInsertOf(pendingFor(debit("1500.00")));

        assertThatExceptionOfType(RequestInProgressException.class)
                .isThrownBy(() -> service.execute(command(debit("1500.00"))));
        assertThat(provider.calls()).isZero();
    }

    @Test
    void shouldReportReusedKeyWhenConcurrentRequestWithOtherPayloadWinsTheInsert() {
        repository.simulateConcurrentInsertOf(pendingFor(debit("1600.00")));

        assertThatExceptionOfType(IdempotencyKeyReusedException.class)
                .isThrownBy(() -> service.execute(command(debit("1500.00"))));
        assertThat(provider.calls()).isZero();
    }

    @Test
    void shouldReplayWhenConcurrentRequestAlreadyFinishedBeforeOurInsert() {
        var winner = pendingFor(debit("1500.00"));
        winner.markExecuted("txn-789", new BigDecimal("5500.00"), PROVIDER_EXECUTED_AT, clock);
        repository.simulateConcurrentInsertOf(winner);

        var result = service.execute(command(debit("1500.00")));

        assertThat(result.replayed()).isTrue();
        assertThat(result.transaction().status()).isEqualTo(TransactionStatus.EXECUTED);
        assertThat(provider.calls()).isZero();
    }

    @Test
    void shouldReturnStoredStateWhenSweeperWinsTheRace() {
        provider.willReturn(new Approved("txn-789", new BigDecimal("5500.00"), PROVIDER_EXECUTED_AT));
        repository.simulateSweeperBeforeNextUpdate();

        var result = service.execute(command(debit("1500.00")));

        assertThat(result.replayed()).isFalse();
        assertThat(result.transaction().status()).isEqualTo(TransactionStatus.UNKNOWN);
        assertThat(storedStatus()).isEqualTo(TransactionStatus.UNKNOWN);
    }

    private TransactionStatus storedStatus() {
        return repository.findById(ID).orElseThrow().status();
    }

    private Transaction pendingFor(TransactionRequest request) {
        var otherId = UUID.fromString("0192f3b4-0000-7000-8000-000000000099");
        return Transaction.pending(request, KEY, RequestHasher.hash(request), otherId, clock);
    }

    private static ExecuteTransactionCommand command(TransactionRequest request) {
        return new ExecuteTransactionCommand(request, KEY);
    }

    private static TransactionRequest debit(String amount) {
        return new TransactionRequest("acc-1", TransactionType.DEBIT, new BigDecimal(amount), "MXN", "rent");
    }
}
