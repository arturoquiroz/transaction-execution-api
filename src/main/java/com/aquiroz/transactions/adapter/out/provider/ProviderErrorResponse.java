package com.aquiroz.transactions.adapter.out.provider;

record ProviderErrorResponse(String status, String code, String message) {

    boolean isRejection() {
        return "REJECTED".equals(status) && code != null;
    }
}
