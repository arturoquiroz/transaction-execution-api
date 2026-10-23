package com.aquiroz.transactions.application;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.aquiroz.transactions.domain.model.TransactionRequest;
import com.aquiroz.transactions.domain.model.TransactionType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ExecuteTransactionCommandTest {

    private static final TransactionRequest REQUEST =
            new TransactionRequest("acc-1", TransactionType.CREDIT, new BigDecimal("10.00"), "MXN", null);

    @Test
    void shouldRequireIdempotencyKey() {
        assertThatNullPointerException().isThrownBy(() -> new ExecuteTransactionCommand(REQUEST, null));
    }

    @Test
    void shouldRejectBlankIdempotencyKey() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ExecuteTransactionCommand(REQUEST, "  "));
    }
}
