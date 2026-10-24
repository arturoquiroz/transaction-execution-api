package com.aquiroz.transactions.e2e;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.aquiroz.transactions.IntegrationTest;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Real Tomcat, PostgreSQL (Testcontainers) and the provider mock loaded from the same wiremock/mappings that
 * docker compose uses, so the demo scenarios are tested too.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@IntegrationTest
class TransactionApiEndToEndTest {

    @RegisterExtension
    static WireMockExtension provider = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().usingFilesUnderDirectory("wiremock"))
            .build();

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("provider.base-url", provider::baseUrl);
    }

    @Autowired
    private RestTestClient client;

    @Test
    void shouldExecuteThenFindTheTransaction() {
        var location = client.post().uri("/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body("""
                        {"accountId": "acc-e2e", "type": "CREDIT", "amount": 1500.00, "currency": "MXN"}""")
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("X-Trace-Id")
                .expectBody()
                .jsonPath("$.status").isEqualTo("EXECUTED")
                .jsonPath("$.balanceAfter").isEqualTo(5500.00)
                .returnResult()
                .getResponseHeaders().getLocation();

        client.get().uri(location)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("EXECUTED")
                .jsonPath("$.accountId").isEqualTo("acc-e2e");

        client.get().uri("/transactions?accountId=acc-e2e")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(1)
                .jsonPath("$.hasNext").isEqualTo(false);
    }

    @Test
    void shouldRecordProviderRejectionAsRejected() {
        postFor("acc-insufficient")
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.status").isEqualTo("REJECTED")
                .jsonPath("$.failureCode").isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void shouldAnswer202WhenProviderFailsWithoutBody() {
        postFor("acc-server-error")
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UNKNOWN");
    }

    @Test
    void shouldAnswer503WhenProviderRejectsTheRequestItself() {
        postFor("acc-bad-request")
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.status").isEqualTo("FAILED");
    }

    private RestTestClient.ResponseSpec postFor(String accountId) {
        return client.post().uri("/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body("{\"accountId\": \"" + accountId + "\", \"type\": \"DEBIT\", \"amount\": 100.00, \"currency\": \"MXN\"}")
                .exchange();
    }
}
