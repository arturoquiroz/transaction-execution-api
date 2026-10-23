package com.aquiroz.transactions.adapter.out.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.aquiroz.transactions.domain.model.TransactionRequest;
import com.aquiroz.transactions.domain.model.TransactionType;
import com.aquiroz.transactions.domain.port.ProviderOutcome.NotExecuted;
import io.github.resilience4j.retry.RetryRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
class HttpExecutionProviderUnreachableTest {

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("provider.base-url", () -> "http://localhost:" + closedPort());
    }

    @Autowired
    private HttpExecutionProvider provider;

    @Autowired
    private RetryRegistry retries;

    @Test
    void shouldRetryOnceAndReturnNotExecutedWhenConnectionIsRefused() {
        var request = new TransactionRequest("acc-1", TransactionType.DEBIT, new BigDecimal("10.00"), "MXN", null);
        var retryMetrics = retries.retry(HttpExecutionProvider.INSTANCE).getMetrics();

        var outcome = provider.execute(request);

        assertThat(outcome).isEqualTo(new NotExecuted("provider unreachable"));
        assertThat(retryMetrics.getNumberOfFailedCallsWithRetryAttempt()).isEqualTo(1);
    }

    // A port that was just free and is now closed: nothing listens there, so the connection is refused
    private static int closedPort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
