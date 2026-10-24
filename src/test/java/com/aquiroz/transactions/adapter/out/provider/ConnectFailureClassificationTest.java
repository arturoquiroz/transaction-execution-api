package com.aquiroz.transactions.adapter.out.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class ConnectFailureClassificationTest {

    @Test
    void shouldTreatConnectionRefusedAsConnectFailure() {
        assertThat(HttpExecutionProvider.isConnectFailure(wrap(new ConnectException("refused")))).isTrue();
    }

    @Test
    void shouldTreatConnectTimeoutAsConnectFailure() {
        assertThat(HttpExecutionProvider.isConnectFailure(wrap(new HttpConnectTimeoutException("connect")))).isTrue();
    }

    @Test
    void shouldNotTreatReadTimeoutAsConnectFailure() {
        assertThat(HttpExecutionProvider.isConnectFailure(wrap(new HttpTimeoutException("read")))).isFalse();
    }

    private static ResourceAccessException wrap(IOException cause) {
        return new ResourceAccessException("I/O error", cause);
    }
}
