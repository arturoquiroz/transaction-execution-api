package com.aquiroz.transactions.adapter.in.web;

import java.util.UUID;

class TransactionNotFoundException extends RuntimeException {

    TransactionNotFoundException(UUID id) {
        super("Transaction " + id + " was not found");
    }
}
