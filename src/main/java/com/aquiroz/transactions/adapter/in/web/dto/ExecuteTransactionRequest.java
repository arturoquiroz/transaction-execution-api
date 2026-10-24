package com.aquiroz.transactions.adapter.in.web.dto;

import com.aquiroz.transactions.domain.model.TransactionRequest;
import com.aquiroz.transactions.domain.model.TransactionType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Validates the shape of the request (400). Whether the values are acceptable is decided by the business rules (422).
 */
public record ExecuteTransactionRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9-]+") String accountId,
        @NotNull TransactionType type,
        @NotNull @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency,
        @Size(max = 255) String description) {

    public TransactionRequest toDomain() {
        return new TransactionRequest(accountId, type, amount, currency, description);
    }
}
