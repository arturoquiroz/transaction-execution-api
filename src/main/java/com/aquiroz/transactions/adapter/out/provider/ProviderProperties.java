package com.aquiroz.transactions.adapter.out.provider;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("provider")
public record ProviderProperties(URI baseUrl, Duration connectTimeout, Duration readTimeout) {

    public ProviderProperties {
        Objects.requireNonNull(baseUrl, "base-url");
        Objects.requireNonNull(connectTimeout, "connect-timeout");
        Objects.requireNonNull(readTimeout, "read-timeout");
    }
}
