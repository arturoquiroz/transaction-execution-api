package com.aquiroz.transactions.adapter.out.persistence;

import com.aquiroz.transactions.domain.model.Transaction;
import com.aquiroz.transactions.domain.model.TransactionRequest;
import com.aquiroz.transactions.domain.model.TransactionStatus;
import com.aquiroz.transactions.domain.model.TransactionType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

class TransactionRowMapper implements RowMapper<Transaction> {

    @Override
    public Transaction mapRow(ResultSet rs, int rowNum) throws SQLException {
        var request = new TransactionRequest(
                rs.getString("account_id"),
                TransactionType.valueOf(rs.getString("type")),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("description"));
        return Transaction.restore(
                rs.getObject("id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                request,
                TransactionStatus.valueOf(rs.getString("status")),
                rs.getString("provider_transaction_id"),
                rs.getBigDecimal("balance_after"),
                rs.getString("failure_code"),
                rs.getString("failure_message"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "executed_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
