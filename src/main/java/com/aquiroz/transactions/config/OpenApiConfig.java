package com.aquiroz.transactions.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI transactionExecutionApi() {
        return new OpenAPI().info(new Info()
                .title("Transaction Execution API")
                .version("v1")
                .description("Executes CREDIT/DEBIT transactions against an external provider. "
                        + "POST /transactions requires an Idempotency-Key header."));
    }
}
