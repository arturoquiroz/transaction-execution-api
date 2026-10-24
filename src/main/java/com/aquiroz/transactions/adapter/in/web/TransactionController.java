package com.aquiroz.transactions.adapter.in.web;

import com.aquiroz.transactions.adapter.in.web.dto.ExecuteTransactionRequest;
import com.aquiroz.transactions.adapter.in.web.dto.PageResponse;
import com.aquiroz.transactions.adapter.in.web.dto.TransactionResponse;
import com.aquiroz.transactions.application.ExecuteTransactionCommand;
import com.aquiroz.transactions.application.ExecuteTransactionService;
import com.aquiroz.transactions.application.QueryTransactionsService;
import com.aquiroz.transactions.domain.model.TransactionStatus;
import com.aquiroz.transactions.domain.model.TransactionType;
import com.aquiroz.transactions.domain.port.PageQuery;
import com.aquiroz.transactions.domain.port.TransactionFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
public class TransactionController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";
    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

    private final ExecuteTransactionService executeService;
    private final QueryTransactionsService queryService;

    @PostMapping
    public ResponseEntity<TransactionResponse> execute(
            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = MAX_IDEMPOTENCY_KEY_LENGTH) String idempotencyKey,
            @Valid @RequestBody ExecuteTransactionRequest body) {
        var result = executeService.execute(new ExecuteTransactionCommand(body.toDomain(), idempotencyKey));
        var transaction = result.transaction();
        return ResponseEntity.status(httpStatusFor(transaction.status()))
                .location(URI.create("/transactions/" + transaction.id()))
                .header(REPLAYED_HEADER, String.valueOf(result.replayed()))
                .body(TransactionResponse.from(transaction));
    }

    @GetMapping
    public PageResponse<TransactionResponse> search(@RequestParam(required = false) String accountId,
                                                    @RequestParam(required = false) TransactionStatus status,
                                                    @RequestParam(required = false) TransactionType type,
                                                    @RequestParam(defaultValue = "0") @Min(0) int page,
                                                    @RequestParam(defaultValue = "20") @Min(1)
                                                    @Max(PageQuery.MAX_LIMIT) int limit) {
        var result = queryService.search(new TransactionFilter(accountId, status, type), new PageQuery(page, limit));
        return PageResponse.from(result, TransactionResponse::from);
    }

    @GetMapping("/{id}")
    public TransactionResponse findById(@PathVariable UUID id) {
        return queryService.findById(id)
                .map(TransactionResponse::from)
                .orElseThrow(() -> new TransactionNotFoundException(id));
    }


    private static HttpStatus httpStatusFor(TransactionStatus status) {
        return switch (status) {
            case EXECUTED, REJECTED -> HttpStatus.CREATED;
            case UNKNOWN -> HttpStatus.ACCEPTED;
            case FAILED -> HttpStatus.SERVICE_UNAVAILABLE;
            case PENDING -> throw new IllegalStateException("The use case never returns a PENDING transaction");
        };
    }
}
