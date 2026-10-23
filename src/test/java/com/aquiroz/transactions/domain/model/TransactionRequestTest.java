package com.aquiroz.transactions.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TransactionRequestTest {

    @Test
    void shouldNormalizeAmountToScaleTwo() {
        var request = requestWithAmount(new BigDecimal("100"));

        assertThat(request.amount()).isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    void shouldAcceptTrailingZerosBeyondTwoDecimals() {
        var request = requestWithAmount(new BigDecimal("100.500"));

        assertThat(request.amount()).isEqualTo(new BigDecimal("100.50"));
    }

    @Test
    void shouldRejectAmountWithMoreThanTwoDecimals() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> requestWithAmount(new BigDecimal("100.001")))
                .withMessageContaining("2 decimal places");
    }

    @Test
    void shouldAllowMissingDescription() {
        var request = new TransactionRequest("acc-1", TransactionType.CREDIT, new BigDecimal("10.00"), "MXN", null);

        assertThat(request.description()).isNull();
    }

    @Test
    void shouldRejectMissingRequiredFields() {
        var amount = new BigDecimal("10.00");

        assertThatNullPointerException()
                .isThrownBy(() -> new TransactionRequest(null, TransactionType.CREDIT, amount, "MXN", null));
        assertThatNullPointerException()
                .isThrownBy(() -> new TransactionRequest("acc-1", null, amount, "MXN", null));
        assertThatNullPointerException()
                .isThrownBy(() -> new TransactionRequest("acc-1", TransactionType.CREDIT, null, "MXN", null));
        assertThatNullPointerException()
                .isThrownBy(() -> new TransactionRequest("acc-1", TransactionType.CREDIT, amount, null, null));
    }

    private static TransactionRequest requestWithAmount(BigDecimal amount) {
        return new TransactionRequest("acc-1", TransactionType.CREDIT, amount, "MXN", "test");
    }
}
