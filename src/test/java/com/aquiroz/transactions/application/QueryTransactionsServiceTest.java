package com.aquiroz.transactions.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aquiroz.transactions.domain.model.Transaction;
import com.aquiroz.transactions.domain.model.TransactionRequest;
import com.aquiroz.transactions.domain.model.TransactionStatus;
import com.aquiroz.transactions.domain.model.TransactionType;
import com.aquiroz.transactions.domain.port.PageQuery;
import com.aquiroz.transactions.domain.port.PageResult;
import com.aquiroz.transactions.domain.port.TransactionFilter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueryTransactionsServiceTest {

    private static final UUID ID = UUID.fromString("0192f3b4-0000-7000-8000-000000000001");

    private final InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
    private final QueryTransactionsService service = new QueryTransactionsService(repository);

    @Test
    void shouldPassFilterAndPageToRepository() {
        var filter = new TransactionFilter("acc-1", TransactionStatus.EXECUTED, TransactionType.DEBIT);
        var page = new PageQuery(2, 20);
        var expected = new PageResult<Transaction>(List.of(), 2, 20, false);
        repository.willReturnOnSearch(expected);

        var result = service.search(filter, page);

        assertThat(result).isEqualTo(expected);
        assertThat(repository.lastFilter()).isEqualTo(filter);
        assertThat(repository.lastPage()).isEqualTo(page);
    }

    @Test
    void shouldFindStoredTransactionById() {
        var request = new TransactionRequest("acc-1", TransactionType.CREDIT, new BigDecimal("10.00"), "MXN", null);
        var clock = Clock.fixed(Instant.parse("2026-10-23T16:30:00Z"), ZoneOffset.UTC);
        repository.store(Transaction.pending(request, "key-1", "hash-1", ID, clock));

        assertThat(service.findById(ID)).hasValueSatisfying(tx -> assertThat(tx.id()).isEqualTo(ID));
        assertThat(service.findById(UUID.randomUUID())).isEmpty();
    }
}
