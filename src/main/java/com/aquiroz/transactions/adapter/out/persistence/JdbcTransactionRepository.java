package com.aquiroz.transactions.adapter.out.persistence;

import com.aquiroz.transactions.domain.model.Transaction;
import com.aquiroz.transactions.domain.model.TransactionStatus;
import com.aquiroz.transactions.domain.port.DuplicateIdempotencyKeyException;
import com.aquiroz.transactions.domain.port.PageQuery;
import com.aquiroz.transactions.domain.port.PageResult;
import com.aquiroz.transactions.domain.port.TransactionFilter;
import com.aquiroz.transactions.domain.port.TransactionRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every method is its own short database transaction, never spanning the provider call.
 * Each status change also appends a row to transaction_events, in the same transaction.
 */
@Repository
@RequiredArgsConstructor
public class JdbcTransactionRepository implements TransactionRepository {

    private static final String COLUMNS = """
            id, idempotency_key, request_hash, account_id, type, amount, currency, description, status,
            provider_transaction_id, balance_after, failure_code, failure_message, created_at, updated_at, executed_at
            """;

    private static final String CHANGED_BY_API = "API";
    private static final String CHANGED_BY_SWEEPER = "SWEEPER";

    private final JdbcClient jdbc;
    private final TransactionRowMapper rowMapper = new TransactionRowMapper();

    @Override
    @Transactional
    public void insert(Transaction tx) {
        try {
            jdbc.sql("INSERT INTO transactions (" + COLUMNS + """
                            ) VALUES (:id, :idempotencyKey, :requestHash, :accountId, :type, :amount, :currency,
                                      :description, :status, :providerTransactionId, :balanceAfter, :failureCode,
                                      :failureMessage, :createdAt, :updatedAt, :executedAt)
                            """)
                    .param("id", tx.id())
                    .param("idempotencyKey", tx.idempotencyKey())
                    .param("requestHash", tx.requestHash())
                    .param("accountId", tx.request().accountId())
                    .param("type", tx.request().type().name())
                    .param("amount", tx.request().amount())
                    .param("currency", tx.request().currency())
                    .param("description", tx.request().description())
                    .param("status", tx.status().name())
                    .param("providerTransactionId", tx.providerTransactionId())
                    .param("balanceAfter", tx.balanceAfter())
                    .param("failureCode", tx.failureCode())
                    .param("failureMessage", tx.failureMessage())
                    .param("createdAt", offset(tx.createdAt()))
                    .param("updatedAt", offset(tx.updatedAt()))
                    .param("executedAt", offset(tx.executedAt()))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateIdempotencyKeyException(tx.idempotencyKey(), e);
        }
        appendEvent(tx, null, tx.createdAt());
    }

    @Override
    @Transactional
    public boolean updateFromPending(Transaction tx) {
        int updated = jdbc.sql("""
                        UPDATE transactions
                           SET status = :status,
                               provider_transaction_id = :providerTransactionId,
                               balance_after = :balanceAfter,
                               failure_code = :failureCode,
                               failure_message = :failureMessage,
                               updated_at = :updatedAt,
                               executed_at = :executedAt
                         WHERE id = :id AND status = 'PENDING'
                        """)
                .param("id", tx.id())
                .param("status", tx.status().name())
                .param("providerTransactionId", tx.providerTransactionId())
                .param("balanceAfter", tx.balanceAfter())
                .param("failureCode", tx.failureCode())
                .param("failureMessage", tx.failureMessage())
                .param("updatedAt", offset(tx.updatedAt()))
                .param("executedAt", offset(tx.executedAt()))
                .update();
        if (updated == 0) {
            return false;
        }
        appendEvent(tx, TransactionStatus.PENDING, tx.updatedAt());
        return true;
    }

    @Override
    public Optional<Transaction> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transactions WHERE id = :id")
                .param("id", id)
                .query(rowMapper)
                .optional();
    }

    @Override
    public Optional<Transaction> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transactions WHERE idempotency_key = :idempotencyKey")
                .param("idempotencyKey", idempotencyKey)
                .query(rowMapper)
                .optional();
    }

    @Override
    public PageResult<Transaction> search(TransactionFilter filter, PageQuery page) {
        var conditions = new ArrayList<String>();
        var params = new HashMap<String, Object>();
        if (filter.accountId() != null) {
            conditions.add("account_id = :accountId");
            params.put("accountId", filter.accountId());
        }
        if (filter.status() != null) {
            conditions.add("status = :status");
            params.put("status", filter.status().name());
        }
        if (filter.type() != null) {
            conditions.add("type = :type");
            params.put("type", filter.type().name());
        }
        var where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        params.put("limitPlusOne", page.limit() + 1);
        params.put("offset", page.offset());

        // limit + 1 tells whether a next page exists without a COUNT(*) (LLD §5.10)
        var rows = jdbc.sql("SELECT " + COLUMNS + " FROM transactions" + where
                        + " ORDER BY created_at DESC, id DESC LIMIT :limitPlusOne OFFSET :offset")
                .params(params)
                .query(rowMapper)
                .list();
        boolean hasNext = rows.size() > page.limit();
        var items = hasNext ? rows.subList(0, page.limit()) : rows;
        return new PageResult<>(items, page.page(), page.limit(), hasNext);
    }

    @Override
    public int markStalePendingAsUnknown(Instant olderThan, Instant now) {
        return jdbc.sql("""
                        WITH swept AS (
                            UPDATE transactions
                               SET status = :unknown,
                                   failure_code = :failureCode,
                                   failure_message = 'No final outcome was saved in time; requires reconciliation',
                                   updated_at = :now
                             WHERE status = 'PENDING' AND created_at < :olderThan
                            RETURNING id
                        )
                        INSERT INTO transaction_events
                               (transaction_id, from_status, to_status, failure_code, changed_by, occurred_at)
                        SELECT id, 'PENDING', :unknown, :failureCode, :changedBy, :now FROM swept
                        """)
                .param("unknown", TransactionStatus.UNKNOWN.name())
                .param("failureCode", Transaction.STALE_PENDING)
                .param("changedBy", CHANGED_BY_SWEEPER)
                .param("now", offset(now))
                .param("olderThan", offset(olderThan))
                .update();
    }

    private void appendEvent(Transaction tx, TransactionStatus fromStatus, Instant occurredAt) {
        jdbc.sql("""
                        INSERT INTO transaction_events
                               (transaction_id, from_status, to_status, failure_code, changed_by, occurred_at)
                        VALUES (:transactionId, :fromStatus, :toStatus, :failureCode, :changedBy, :occurredAt)
                        """)
                .param("transactionId", tx.id())
                .param("fromStatus", fromStatus == null ? null : fromStatus.name())
                .param("toStatus", tx.status().name())
                .param("failureCode", tx.failureCode())
                .param("changedBy", CHANGED_BY_API)
                .param("occurredAt", offset(occurredAt))
                .update();
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
