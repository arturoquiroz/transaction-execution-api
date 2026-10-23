package com.aquiroz.transactions.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TransactionTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-23T16:30:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-10-23T16:30:02Z");
    private static final Instant PROVIDER_EXECUTED_AT = Instant.parse("2026-10-23T16:30:01Z");

    private static final Clock CREATION_CLOCK = Clock.fixed(CREATED_AT, ZoneOffset.UTC);
    private static final Clock UPDATE_CLOCK = Clock.fixed(UPDATED_AT, ZoneOffset.UTC);

    private static final UUID ID = UUID.fromString("0192f3b4-0000-7000-8000-000000000001");
    private static final TransactionRequest REQUEST =
            new TransactionRequest("acc-1", TransactionType.DEBIT, new BigDecimal("1500.00"), "MXN", "rent");

    @Test
    void shouldCreatePendingTransactionFromRequest() {
        var tx = Transaction.pending(REQUEST, "key-1", "hash-1", ID, CREATION_CLOCK);

        assertThat(tx.id()).isEqualTo(ID);
        assertThat(tx.idempotencyKey()).isEqualTo("key-1");
        assertThat(tx.requestHash()).isEqualTo("hash-1");
        assertThat(tx.request()).isEqualTo(REQUEST);
        assertThat(tx.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(tx.createdAt()).isEqualTo(CREATED_AT);
        assertThat(tx.updatedAt()).isEqualTo(CREATED_AT);
        assertThat(tx.providerTransactionId()).isNull();
        assertThat(tx.balanceAfter()).isNull();
        assertThat(tx.failureCode()).isNull();
        assertThat(tx.failureMessage()).isNull();
        assertThat(tx.executedAt()).isNull();
    }

    @Test
    void shouldMarkExecutedFromPending() {
        var tx = pendingTransaction();

        tx.markExecuted("txn-789", new BigDecimal("5500.00"), PROVIDER_EXECUTED_AT, UPDATE_CLOCK);

        assertThat(tx.status()).isEqualTo(TransactionStatus.EXECUTED);
        assertThat(tx.providerTransactionId()).isEqualTo("txn-789");
        assertThat(tx.balanceAfter()).isEqualTo(new BigDecimal("5500.00"));
        assertThat(tx.executedAt()).isEqualTo(PROVIDER_EXECUTED_AT);
        assertThat(tx.updatedAt()).isEqualTo(UPDATED_AT);
        assertThat(tx.failureCode()).isNull();
    }

    @Test
    void shouldMarkRejectedFromPendingKeepingProviderCode() {
        var tx = pendingTransaction();

        tx.markRejected("INSUFFICIENT_FUNDS", "Not enough balance", UPDATE_CLOCK);

        assertThat(tx.status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(tx.failureCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(tx.failureMessage()).isEqualTo("Not enough balance");
        assertThat(tx.updatedAt()).isEqualTo(UPDATED_AT);
        assertThat(tx.executedAt()).isNull();
    }

    @Test
    void shouldMarkFailedFromPendingWithProviderUnavailableCode() {
        var tx = pendingTransaction();

        tx.markFailed("circuit open", UPDATE_CLOCK);

        assertThat(tx.status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(tx.failureCode()).isEqualTo(Transaction.PROVIDER_UNAVAILABLE);
        assertThat(tx.failureMessage()).isEqualTo("circuit open");
        assertThat(tx.updatedAt()).isEqualTo(UPDATED_AT);
    }

    @Test
    void shouldMarkUnknownFromPendingWithOutcomeUnknownCode() {
        var tx = pendingTransaction();

        tx.markUnknown("provider read timeout", UPDATE_CLOCK);

        assertThat(tx.status()).isEqualTo(TransactionStatus.UNKNOWN);
        assertThat(tx.failureCode()).isEqualTo(Transaction.PROVIDER_OUTCOME_UNKNOWN);
        assertThat(tx.failureMessage()).isEqualTo("provider read timeout");
        assertThat(tx.updatedAt()).isEqualTo(UPDATED_AT);
    }

    @ParameterizedTest(name = "missing {0}")
    @MethodSource("executedCallsWithMissingProviderData")
    void shouldRequireProviderDataToMarkExecuted(String missingField, Consumer<Transaction> applyTransition) {
        var tx = pendingTransaction();

        assertThatNullPointerException()
                .isThrownBy(() -> applyTransition.accept(tx))
                .withMessage(missingField);
        assertThat(tx.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(tx.updatedAt()).isEqualTo(CREATED_AT);
    }

    static Stream<Arguments> executedCallsWithMissingProviderData() {
        var balance = new BigDecimal("5500.00");
        return Stream.of(
                Arguments.of("providerTransactionId", (Consumer<Transaction>) tx ->
                        tx.markExecuted(null, balance, PROVIDER_EXECUTED_AT, UPDATE_CLOCK)),
                Arguments.of("balanceAfter", (Consumer<Transaction>) tx ->
                        tx.markExecuted("txn-789", null, PROVIDER_EXECUTED_AT, UPDATE_CLOCK)),
                Arguments.of("executedAt", (Consumer<Transaction>) tx ->
                        tx.markExecuted("txn-789", balance, null, UPDATE_CLOCK)));
    }

    @Test
    void shouldRequireCodeToMarkRejected() {
        var tx = pendingTransaction();

        assertThatNullPointerException()
                .isThrownBy(() -> tx.markRejected(null, "Not enough balance", UPDATE_CLOCK))
                .withMessage("code");
        assertThat(tx.status()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void shouldAllowRejectionWithoutProviderMessage() {
        var tx = pendingTransaction();

        tx.markRejected("INSUFFICIENT_FUNDS", null, UPDATE_CLOCK);

        assertThat(tx.status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(tx.failureMessage()).isNull();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("transitionsFromFinalStates")
    void shouldRejectAnyTransitionOutOfFinalState(TransactionStatus finalStatus, String transition,
                                                  Consumer<Transaction> applyTransition) {
        var tx = restoredWithStatus(finalStatus);

        assertThatIllegalStateException()
                .isThrownBy(() -> applyTransition.accept(tx))
                .withMessageContaining(finalStatus.name());
        assertThat(tx.status()).isEqualTo(finalStatus);
        assertThat(tx.updatedAt()).isEqualTo(CREATED_AT);
    }

    static Stream<Arguments> transitionsFromFinalStates() {
        return Stream.of(TransactionStatus.EXECUTED, TransactionStatus.REJECTED,
                        TransactionStatus.FAILED, TransactionStatus.UNKNOWN)
                .flatMap(status -> Stream.of(
                        Arguments.of(status, "markExecuted", (Consumer<Transaction>) tx ->
                                tx.markExecuted("txn-1", BigDecimal.TEN, PROVIDER_EXECUTED_AT, UPDATE_CLOCK)),
                        Arguments.of(status, "markRejected", (Consumer<Transaction>) tx ->
                                tx.markRejected("CODE", "message", UPDATE_CLOCK)),
                        Arguments.of(status, "markFailed", (Consumer<Transaction>) tx ->
                                tx.markFailed("reason", UPDATE_CLOCK)),
                        Arguments.of(status, "markUnknown", (Consumer<Transaction>) tx ->
                                tx.markUnknown("reason", UPDATE_CLOCK))));
    }

    @Test
    void shouldRestoreAnyStateWithoutValidatingTransition() {
        var tx = Transaction.restore(ID, "key-1", "hash-1", REQUEST, TransactionStatus.EXECUTED,
                "txn-789", new BigDecimal("5500.00"), null, null, CREATED_AT, UPDATED_AT, PROVIDER_EXECUTED_AT);

        assertThat(tx.status()).isEqualTo(TransactionStatus.EXECUTED);
        assertThat(tx.providerTransactionId()).isEqualTo("txn-789");
        assertThat(tx.balanceAfter()).isEqualTo(new BigDecimal("5500.00"));
        assertThat(tx.createdAt()).isEqualTo(CREATED_AT);
        assertThat(tx.updatedAt()).isEqualTo(UPDATED_AT);
        assertThat(tx.executedAt()).isEqualTo(PROVIDER_EXECUTED_AT);
    }

    private static Transaction pendingTransaction() {
        return Transaction.pending(REQUEST, "key-1", "hash-1", ID, CREATION_CLOCK);
    }

    private static Transaction restoredWithStatus(TransactionStatus status) {
        return Transaction.restore(ID, "key-1", "hash-1", REQUEST, status,
                null, null, null, null, CREATED_AT, CREATED_AT, null);
    }
}
