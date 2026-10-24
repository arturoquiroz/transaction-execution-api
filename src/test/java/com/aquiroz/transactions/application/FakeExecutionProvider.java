package com.aquiroz.transactions.application;

import com.aquiroz.transactions.domain.model.TransactionRequest;
import com.aquiroz.transactions.domain.port.ExecutionProvider;
import com.aquiroz.transactions.domain.port.ProviderOutcome;
import java.util.function.Consumer;

class FakeExecutionProvider implements ExecutionProvider {

    private ProviderOutcome outcome;
    private Consumer<TransactionRequest> onCall = _ -> { };
    private int calls;

    void willReturn(ProviderOutcome outcome) {
        this.outcome = outcome;
    }

    /** Runs before returning the outcome, to inspect the world as the provider sees it. */
    void onCall(Consumer<TransactionRequest> onCall) {
        this.onCall = onCall;
    }

    int calls() {
        return calls;
    }

    @Override
    public ProviderOutcome execute(TransactionRequest request) {
        calls++;
        onCall.accept(request);
        return outcome;
    }
}
