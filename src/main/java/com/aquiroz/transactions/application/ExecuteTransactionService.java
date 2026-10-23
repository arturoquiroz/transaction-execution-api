package com.aquiroz.transactions.application;

import com.aquiroz.transactions.domain.model.Transaction;
import com.aquiroz.transactions.domain.model.TransactionStatus;
import com.aquiroz.transactions.domain.port.DuplicateIdempotencyKeyException;
import com.aquiroz.transactions.domain.port.ExecutionProvider;
import com.aquiroz.transactions.domain.port.ProviderOutcome;
import com.aquiroz.transactions.domain.port.ProviderOutcome.Approved;
import com.aquiroz.transactions.domain.port.ProviderOutcome.NotExecuted;
import com.aquiroz.transactions.domain.port.ProviderOutcome.Rejected;
import com.aquiroz.transactions.domain.port.ProviderOutcome.Uncertain;
import com.aquiroz.transactions.domain.port.TransactionIdGenerator;
import com.aquiroz.transactions.domain.port.TransactionRepository;
import com.aquiroz.transactions.domain.rule.TransactionPolicy;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class ExecuteTransactionService {

    private final TransactionRepository repository;
    private final ExecutionProvider provider;
    private final TransactionPolicy policy;
    private final TransactionIdGenerator idGenerator;
    private final Clock clock;

    public ExecutionResult execute(ExecuteTransactionCommand command) {
        var request = command.request();
        var key = command.idempotencyKey();
        var hash = RequestHasher.hash(request);

        var existing = repository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), hash);
        }

        policy.enforce(request);

        var transaction = Transaction.pending(request, key, hash, idGenerator.nextId(), clock);
        try {
            repository.insert(transaction);
        } catch (DuplicateIdempotencyKeyException _) {
            return replayOrConflict(repository.findByIdempotencyKey(key).orElseThrow(), hash);
        }

        applyOutcome(transaction, provider.execute(request));

        if (!repository.updateFromPending(transaction)) {
            log.warn("Transaction {} left PENDING before its outcome was saved: status={}, providerTransactionId={}, "
                            + "failureCode={}; returning stored state",
                    transaction.id(), transaction.status(), transaction.providerTransactionId(),
                    transaction.failureCode());
            return new ExecutionResult(repository.findById(transaction.id()).orElseThrow(), false);
        }
        return new ExecutionResult(transaction, false);
    }

    private ExecutionResult replayOrConflict(Transaction existing, String hash) {
        if (!existing.requestHash().equals(hash)) {
            throw new IdempotencyKeyReusedException(existing.idempotencyKey());
        }
        if (existing.status() == TransactionStatus.PENDING) {
            throw new RequestInProgressException(existing.idempotencyKey());
        }
        return new ExecutionResult(existing, true);
    }

    private void applyOutcome(Transaction transaction, ProviderOutcome outcome) {
        switch (outcome) {
            case Approved(var providerTransactionId, var balance, var executedAt) ->
                    transaction.markExecuted(providerTransactionId, balance, executedAt, clock);
            case Rejected(var code, var message) -> transaction.markRejected(code, message, clock);
            case NotExecuted(var reason) -> transaction.markFailed(reason, clock);
            case Uncertain(var reason) -> transaction.markUnknown(reason, clock);
        }
    }
}
