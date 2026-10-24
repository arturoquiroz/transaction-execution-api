package com.aquiroz.transactions.config;

import com.aquiroz.transactions.adapter.out.provider.ProviderProperties;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(ProviderProperties.class)
public class ProviderClientConfig {

    @Bean
    public RestClient providerRestClient(ProviderProperties provider, ObservationRegistry observationRegistry) {
        // The JDK client defaults to HTTP/2 and tries an h2c upgrade on plain HTTP, which drops POST bodies
        var httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(provider.connectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(provider.readTimeout());
        return RestClient.builder()
                .observationRegistry(observationRegistry)
                .baseUrl(provider.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
