package com.aquiroz.transactions.application;

import com.aquiroz.transactions.domain.model.Transaction;
import com.aquiroz.transactions.domain.port.PageQuery;
import com.aquiroz.transactions.domain.port.PageResult;
import com.aquiroz.transactions.domain.port.TransactionFilter;
import com.aquiroz.transactions.domain.port.TransactionRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class QueryTransactionsService {

    private final TransactionRepository repository;

    public PageResult<Transaction> search(TransactionFilter filter, PageQuery page) {
        return repository.search(filter, page);
    }

    public Optional<Transaction> findById(UUID id) {
        return repository.findById(id);
    }
}
