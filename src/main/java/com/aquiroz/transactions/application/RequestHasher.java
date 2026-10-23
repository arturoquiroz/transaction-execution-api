package com.aquiroz.transactions.application;

import com.aquiroz.transactions.domain.model.TransactionRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of the canonical payload, used to detect an idempotency key reused with different data.
 */
final class RequestHasher {

    private RequestHasher() {
    }

    // Built from the normalized request, so "100" and "100.00" produce the same hash
    static String hash(TransactionRequest request) {
        var canonical = String.join("|",
                request.accountId(),
                request.type().name(),
                request.amount().toPlainString(),
                request.currency(),
                request.description() == null ? "" : request.description());
        return HexFormat.of().formatHex(sha256().digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JVM", e);
        }
    }
}
