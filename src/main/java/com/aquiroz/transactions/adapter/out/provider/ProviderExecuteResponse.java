package com.aquiroz.transactions.adapter.out.provider;

import java.math.BigDecimal;
import java.time.Instant;

record ProviderExecuteResponse(String transactionId, String status, BigDecimal balance, Instant executedAt) {

    boolean isCompleteApproval() {
        return "APPROVED".equals(status) && transactionId != null && balance != null && executedAt != null;
    }
}
