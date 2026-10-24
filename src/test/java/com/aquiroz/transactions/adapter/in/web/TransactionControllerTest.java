package com.aquiroz.transactions.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aquiroz.transactions.application.ExecuteTransactionCommand;
import com.aquiroz.transactions.application.ExecuteTransactionService;
import com.aquiroz.transactions.application.ExecutionResult;
import com.aquiroz.transactions.application.IdempotencyKeyReusedException;
import com.aquiroz.transactions.application.QueryTransactionsService;
import com.aquiroz.transactions.application.RequestInProgressException;
import com.aquiroz.transactions.domain.model.Transaction;
import com.aquiroz.transactions.domain.model.TransactionRequest;
import com.aquiroz.transactions.domain.model.TransactionStatus;
import com.aquiroz.transactions.domain.model.TransactionType;
import com.aquiroz.transactions.domain.port.PageQuery;
import com.aquiroz.transactions.domain.port.PageResult;
import com.aquiroz.transactions.domain.port.TransactionFilter;
import com.aquiroz.transactions.domain.rule.BusinessRuleViolationException;
import com.aquiroz.transactions.domain.rule.RuleViolation;
import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * One test per row of ARCHITECTURE §9. Use cases are mocked: their behaviour is covered by their own tests.
 */
@WebMvcTest(TransactionController.class)
@Import(TransactionControllerTest.NoopTracing.class)
class TransactionControllerTest {

    private static final UUID ID = UUID.fromString("0192f3b4-0000-7000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-10-23T16:30:00Z");
    private static final String VALID_BODY = """
            {"accountId": "acc-123456", "type": "DEBIT", "amount": 1500.00, "currency": "MXN",
             "description": "rent"}""";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ExecuteTransactionService executeService;

    @MockitoBean
    private QueryTransactionsService queryService;

    @TestConfiguration
    static class NoopTracing {
        @Bean
        Tracer tracer() {
            return Tracer.NOOP;
        }
    }

    @Test
    void shouldRespond201WithLocationWhenExecuted() throws Exception {
        givenExecutionReturns(TransactionStatus.EXECUTED, false);

        mvc.perform(postTransaction(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/transactions/" + ID))
                .andExpect(header().string(TransactionController.REPLAYED_HEADER, "false"))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.amount").value(1500.00))
                .andExpect(jsonPath("$.providerTransactionId").value("txn-789"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-23T16:30:00Z"));
    }

    @Test
    void shouldRespond201WhenProviderRejected() throws Exception {
        givenExecutionReturns(TransactionStatus.REJECTED, false);

        mvc.perform(postTransaction(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.failureCode").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void shouldRespond202WhenOutcomeIsUnknown() throws Exception {
        givenExecutionReturns(TransactionStatus.UNKNOWN, false);

        mvc.perform(postTransaction(VALID_BODY)).andExpect(status().isAccepted());
    }

    @Test
    void shouldRespond503WhenProviderDidNotExecute() throws Exception {
        givenExecutionReturns(TransactionStatus.FAILED, false);

        mvc.perform(postTransaction(VALID_BODY)).andExpect(status().isServiceUnavailable());
    }

    @Test
    void shouldReplayWithOriginalStatusCode() throws Exception {
        givenExecutionReturns(TransactionStatus.EXECUTED, true);

        mvc.perform(postTransaction(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string(TransactionController.REPLAYED_HEADER, "true"));
    }

    @Test
    void shouldPassRequestAndKeyToUseCase() throws Exception {
        givenExecutionReturns(TransactionStatus.EXECUTED, false);

        mvc.perform(postTransaction(VALID_BODY));

        verify(executeService).execute(new ExecuteTransactionCommand(
                new TransactionRequest("acc-123456", TransactionType.DEBIT, new BigDecimal("1500.00"), "MXN", "rent"),
                "key-1"));
    }

    @Test
    void shouldRespond400WhenIdempotencyKeyIsMissing() throws Exception {
        mvc.perform(post("/transactions").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
        verifyNoInteractions(executeService);
    }

    @Test
    void shouldReportMissingKeyBeforeInvalidBody() throws Exception {
        mvc.perform(post("/transactions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
    }

    @Test
    void shouldTreatBlankIdempotencyKeyAsMissing() throws Exception {
        mvc.perform(postTransaction(VALID_BODY, " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
    }

    @Test
    void shouldReportBlankKeyBeforeInvalidBody() throws Exception {
        mvc.perform(postTransaction("{}", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
    }

    @Test
    void shouldRespond400WhenIdempotencyKeyIsTooLong() throws Exception {
        mvc.perform(postTransaction(VALID_BODY, "k".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void shouldRespond400WithFieldErrorsWhenBodyIsInvalid() throws Exception {
        mvc.perform(postTransaction("""
                        {"accountId": "acc|1", "type": "DEBIT", "amount": 10.001, "currency": "MX"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[?(@.field == 'accountId')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'amount')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'currency')]").exists());
        verifyNoInteractions(executeService);
    }

    @Test
    void shouldRespond400WhenTypeIsNotAKnownValue() throws Exception {
        mvc.perform(postTransaction("""
                        {"accountId": "acc-1", "type": "REFUND", "amount": 10.00, "currency": "MXN"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void shouldRespond422WithRuleCodeWhenBusinessRuleIsViolated() throws Exception {
        given(executeService.execute(any())).willThrow(new BusinessRuleViolationException(
                new RuleViolation("DEBIT_LIMIT_EXCEEDED", "DEBIT amount must not exceed 10000.00")));

        mvc.perform(postTransaction(VALID_BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DEBIT_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.detail").value("DEBIT amount must not exceed 10000.00"))
                .andExpect(jsonPath("$.type").value("https://errors.transactions.local/business-rule-violation"));
    }

    @Test
    void shouldRespond422WhenIdempotencyKeyIsReused() throws Exception {
        given(executeService.execute(any())).willThrow(new IdempotencyKeyReusedException("key-1"));

        mvc.perform(postTransaction(VALID_BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void shouldRespond409WhenOriginalRequestIsInProgress() throws Exception {
        given(executeService.execute(any())).willThrow(new RequestInProgressException("key-1"));

        mvc.perform(postTransaction(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_IN_PROGRESS"));
    }

    @Test
    void shouldRespond500WithoutLeakingDetails() throws Exception {
        given(executeService.execute(any())).willThrow(new IllegalStateException("db password is hunter2"));

        mvc.perform(postTransaction(VALID_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
    }

    @Test
    void shouldSearchWithFiltersAndPagination() throws Exception {
        var filter = new TransactionFilter("acc-1", TransactionStatus.EXECUTED, TransactionType.DEBIT);
        given(queryService.search(filter, new PageQuery(1, 5)))
                .willReturn(new PageResult<>(List.of(transaction(TransactionStatus.EXECUTED)), 1, 5, true));

        mvc.perform(get("/transactions?accountId=acc-1&status=EXECUTED&type=DEBIT&page=1&limit=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(ID.toString()))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.limit").value(5))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    void shouldUseDefaultPagination() throws Exception {
        given(queryService.search(any(), any())).willReturn(new PageResult<>(List.of(), 0, 20, false));

        mvc.perform(get("/transactions")).andExpect(status().isOk());

        verify(queryService).search(new TransactionFilter(null, null, null), new PageQuery(0, 20));
    }

    @Test
    void shouldRespond400WhenStatusFilterIsUnknown() throws Exception {
        mvc.perform(get("/transactions?status=APPROVED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void shouldRespond400WhenLimitIsAboveMaximum() throws Exception {
        mvc.perform(get("/transactions?limit=101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
    }

    @Test
    void shouldFindTransactionById() throws Exception {
        given(queryService.findById(ID)).willReturn(Optional.of(transaction(TransactionStatus.EXECUTED)));

        mvc.perform(get("/transactions/" + ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()));
    }

    @Test
    void shouldRespond404WhenTransactionDoesNotExist() throws Exception {
        given(queryService.findById(ID)).willReturn(Optional.empty());

        mvc.perform(get("/transactions/" + ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));
    }

    @Test
    void shouldRespond400WhenIdIsNotAUuid() throws Exception {
        mvc.perform(get("/transactions/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    private void givenExecutionReturns(TransactionStatus status, boolean replayed) {
        given(executeService.execute(any())).willReturn(new ExecutionResult(transaction(status), replayed));
    }

    private static Transaction transaction(TransactionStatus status) {
        var request = new TransactionRequest("acc-123456", TransactionType.DEBIT, new BigDecimal("1500.00"), "MXN",
                "rent");
        boolean executed = status == TransactionStatus.EXECUTED;
        return Transaction.restore(ID, "key-1", "0".repeat(64), request, status,
                executed ? "txn-789" : null, executed ? new BigDecimal("5500.00") : null,
                status == TransactionStatus.REJECTED ? "INSUFFICIENT_FUNDS" : null, null,
                NOW, NOW, executed ? NOW : null);
    }

    private static MockHttpServletRequestBuilder postTransaction(String body) {
        return postTransaction(body, "key-1");
    }

    private static MockHttpServletRequestBuilder postTransaction(String body, String key) {
        return post("/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(TransactionController.IDEMPOTENCY_KEY, key)
                .content(body);
    }
}
