package com.aquiroz.transactions.adapter.out.provider;

import java.math.BigDecimal;

record ProviderExecuteRequest(String accountId, String type, BigDecimal amount, String currency) {
}
