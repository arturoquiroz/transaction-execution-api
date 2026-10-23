package com.aquiroz.transactions.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aquiroz.transactions.domain.model.TransactionRequest;
import com.aquiroz.transactions.domain.model.TransactionType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RequestHasherTest {

    @Test
    void shouldProduceSha256HexOfCanonicalPayload() {
        var hash = RequestHasher.hash(request("100.00", "rent"));

        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void shouldIgnoreAmountScaleDifferences() {
        assertThat(RequestHasher.hash(request("100", "rent"))).isEqualTo(RequestHasher.hash(request("100.00", "rent")));
    }

    @Test
    void shouldTreatMissingDescriptionAsEmpty() {
        assertThat(RequestHasher.hash(request("100.00", null))).isEqualTo(RequestHasher.hash(request("100.00", "")));
    }

    @Test
    void shouldChangeWhenAnyFieldChanges() {
        assertThat(RequestHasher.hash(request("100.00", "rent"))).isNotEqualTo(RequestHasher.hash(request("100.01", "rent")));
    }

    private static TransactionRequest request(String amount, String description) {
        return new TransactionRequest("acc-1", TransactionType.DEBIT, new BigDecimal(amount), "MXN", description);
    }
}
