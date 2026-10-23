package com.aquiroz.transactions.adapter.out.provider;

import com.aquiroz.transactions.domain.model.TransactionRequest;
import com.aquiroz.transactions.domain.port.ExecutionProvider;
import com.aquiroz.transactions.domain.port.ProviderOutcome;
import com.aquiroz.transactions.domain.port.ProviderOutcome.Approved;
import com.aquiroz.transactions.domain.port.ProviderOutcome.NotExecuted;
import com.aquiroz.transactions.domain.port.ProviderOutcome.Rejected;
import com.aquiroz.transactions.domain.port.ProviderOutcome.Uncertain;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClientException;

/**
 * Classifies every provider response into a {@link ProviderOutcome} (ARCHITECTURE §8, LLD §5.3).
 * Golden rule: when we cannot be sure the request did not arrive, the outcome is Uncertain.
 */
@Slf4j
@Component
public class HttpExecutionProvider implements ExecutionProvider {

    static final String INSTANCE = "provider";

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public HttpExecutionProvider(RestClient providerRestClient, CircuitBreakerRegistry circuitBreakers,
                                 RetryRegistry retries) {
        this.restClient = providerRestClient;
        this.circuitBreaker = circuitBreakers.circuitBreaker(INSTANCE);
        this.retry = retries.retry(INSTANCE);
    }

    @Override
    public ProviderOutcome execute(TransactionRequest request) {
        Supplier<ProviderOutcome> call = () -> callProvider(request);
        var guarded = CircuitBreaker.decorateSupplier(circuitBreaker, Retry.decorateSupplier(retry, call));
        try {
            return guarded.get();
        } catch (CallNotPermittedException _) {
            return notExecuted("circuit open");
        } catch (ProviderUnreachableException _) {
            return notExecuted("provider unreachable");
        } catch (ProviderServerErrorException e) {
            return uncertain(e.getMessage());
        } catch (ResourceAccessException e) {
            return uncertain(e.getCause() instanceof HttpTimeoutException
                    ? "provider read timeout"
                    : "provider I/O error: " + e.getMessage());
        } catch (RestClientException e) {
            return uncertain("provider client error: " + e.getMessage());
        }
    }

    private ProviderOutcome callProvider(TransactionRequest request) {
        var body = new ProviderExecuteRequest(request.accountId(), request.type().name(), request.amount(),
                request.currency());
        try {
            return restClient.post()
                    .uri("/provider/v1/execute")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((_, response) -> classify(response));
        } catch (ResourceAccessException e) {
            if (isConnectFailure(e)) {
                throw new ProviderUnreachableException(e);
            }
            throw e;
        }
    }

    private ProviderOutcome classify(ConvertibleClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.is2xxSuccessful()) {
            var approval = readOrNull(response, ProviderExecuteResponse.class);
            if (approval != null && approval.isCompleteApproval()) {
                return new Approved(approval.transactionId(), approval.balance(), approval.executedAt());
            }
            return uncertain("provider returned " + status.value() + " without a valid APPROVED body");
        }
        if (status.is4xxClientError() || status.is5xxServerError()) {
            var error = readOrNull(response, ProviderErrorResponse.class);
            if (error != null && error.isRejection()) {
                return new Rejected(error.code(), error.message());
            }
            if (status.is5xxServerError()) {
                throw new ProviderServerErrorException(status.value());
            }
            return notExecuted("provider returned " + status.value());
        }
        return uncertain("provider returned unexpected status " + status.value());
    }

    private static <T> T readOrNull(ConvertibleClientHttpResponse response, Class<T> type) {
        try {
            return response.bodyTo(type);
        } catch (RestClientException _) {
            return null;
        }
    }

    static boolean isConnectFailure(ResourceAccessException e) {
        return e.getCause() instanceof ConnectException || e.getCause() instanceof HttpConnectTimeoutException;
    }

    private static ProviderOutcome notExecuted(String reason) {
        log.warn("Provider call not executed: {}", reason);
        return new NotExecuted(reason);
    }

    private static ProviderOutcome uncertain(String reason) {
        log.warn("Provider outcome uncertain: {}", reason);
        return new Uncertain(reason);
    }
}
