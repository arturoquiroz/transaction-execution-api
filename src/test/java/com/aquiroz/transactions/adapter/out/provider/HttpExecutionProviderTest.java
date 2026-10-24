package com.aquiroz.transactions.adapter.out.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.aquiroz.transactions.TestcontainersConfiguration;
import com.aquiroz.transactions.domain.model.TransactionRequest;
import com.aquiroz.transactions.domain.model.TransactionType;
import com.aquiroz.transactions.domain.port.ProviderOutcome.Approved;
import com.aquiroz.transactions.domain.port.ProviderOutcome.NotExecuted;
import com.aquiroz.transactions.domain.port.ProviderOutcome.Rejected;
import com.aquiroz.transactions.domain.port.ProviderOutcome.Uncertain;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class HttpExecutionProviderTest {

    private static final String PATH = "/provider/v1/execute";
    private static final TransactionRequest REQUEST =
            new TransactionRequest("acc-123456", TransactionType.CREDIT, new BigDecimal("1500.00"), "MXN", "ignored");

    @RegisterExtension
    static WireMockExtension providerMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("provider.base-url", providerMock::baseUrl);
        registry.add("provider.read-timeout", () -> "300ms");
    }

    @Autowired
    private HttpExecutionProvider provider;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @Autowired
    private RetryRegistry retries;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void resetCircuitBreaker() {
        circuitBreaker = circuitBreakers.circuitBreaker(HttpExecutionProvider.INSTANCE);
        circuitBreaker.reset();
    }

    @Test
    void shouldReturnApprovedWhenProviderApproves() {
        providerMock.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("""
                {"transactionId": "txn-789", "status": "APPROVED", "balance": 5500.00,
                 "executedAt": "2025-03-15T10:30:00Z"}""")));

        var outcome = provider.execute(REQUEST);

        assertThat(outcome).isEqualTo(
                new Approved("txn-789", new BigDecimal("5500.00"), Instant.parse("2025-03-15T10:30:00Z")));
    }

    @Test
    void shouldSendProviderContractWithoutDescription() {
        providerMock.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("""
                {"transactionId": "txn-789", "status": "APPROVED", "balance": 5500.00,
                 "executedAt": "2025-03-15T10:30:00Z"}""")));

        provider.execute(REQUEST);

        providerMock.verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(equalToJson("""
                {"accountId": "acc-123456", "type": "CREDIT", "amount": 1500.00, "currency": "MXN"}""")));
    }

    @Test
    void shouldReturnRejectedOn4xxWithRejectedBodyWithoutCountingAsFailure() {
        stubError(422, """
                {"status": "REJECTED", "code": "INSUFFICIENT_FUNDS", "message": "Not enough balance"}""");

        var outcome = provider.execute(REQUEST);

        assertThat(outcome).isEqualTo(new Rejected("INSUFFICIENT_FUNDS", "Not enough balance"));
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void shouldReturnRejectedOn5xxWithRejectedBodyWithoutCountingAsFailure() {
        stubError(500, """
                {"status": "REJECTED", "code": "ACCOUNT_BLOCKED", "message": "Account is blocked"}""");

        var outcome = provider.execute(REQUEST);

        assertThat(outcome).isEqualTo(new Rejected("ACCOUNT_BLOCKED", "Account is blocked"));
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void shouldReturnNotExecutedOn4xxWithoutValidBody() {
        stubError(400, "");

        var outcome = provider.execute(REQUEST);

        assertThat(outcome).isEqualTo(new NotExecuted("provider returned 400"));
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void shouldReturnUncertainOn5xxWithoutValidBodyAndNotRetry() {
        stubError(503, "<html>Service Unavailable</html>");

        var outcome = provider.execute(REQUEST);

        assertThat(outcome).isEqualTo(new Uncertain("provider returned 503"));
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        providerMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    // WireMock journals a delayed request only after its delay, so "no retry" is checked on the retry metrics
    @Test
    void shouldReturnUncertainOnReadTimeoutAndNotRetry() {
        providerMock.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("{}").withFixedDelay(1_000)));
        var retryMetrics = retries.retry(HttpExecutionProvider.INSTANCE).getMetrics();
        long retriedBefore = retryMetrics.getNumberOfFailedCallsWithRetryAttempt();

        var outcome = provider.execute(REQUEST);

        assertThat(outcome).isEqualTo(new Uncertain("provider read timeout"));
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        assertThat(retryMetrics.getNumberOfFailedCallsWithRetryAttempt()).isEqualTo(retriedBefore);
    }

    @Test
    void shouldReturnUncertainWhenConnectionDropsAfterRequestWasSent() {
        providerMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        var outcome = provider.execute(REQUEST);

        assertThat(outcome).isInstanceOf(Uncertain.class);
        providerMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void shouldReturnUncertainOn200WithoutCompleteApproval() {
        providerMock.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("""
                {"status": "APPROVED", "balance": 5500.00}""")));

        var outcome = provider.execute(REQUEST);

        assertThat(outcome).isEqualTo(new Uncertain("provider returned 200 without a valid APPROVED body"));
    }

    @Test
    void shouldReturnUncertainOn200WithUnreadableBody() {
        stubError(200, "not json");

        var outcome = provider.execute(REQUEST);

        assertThat(outcome).isEqualTo(new Uncertain("provider returned 200 without a valid APPROVED body"));
    }

    @Test
    void shouldNotTreatRejectedBodyWithoutCodeAsRejection() {
        stubError(422, """
                {"status": "REJECTED", "message": "no code"}""");

        var outcome = provider.execute(REQUEST);

        assertThat(outcome).isEqualTo(new NotExecuted("provider returned 422"));
    }

    @Test
    void shouldReturnUncertainOnUnexpectedStatus() {
        stubError(302, "");

        var outcome = provider.execute(REQUEST);

        assertThat(outcome).isEqualTo(new Uncertain("provider returned unexpected status 302"));
    }

    @Test
    void shouldReturnNotExecutedWithoutCallingProviderWhenCircuitIsOpen() {
        circuitBreaker.transitionToOpenState();

        var outcome = provider.execute(REQUEST);

        assertThat(outcome).isEqualTo(new NotExecuted("circuit open"));
        providerMock.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    private static void stubError(int status, String body) {
        providerMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }
}
