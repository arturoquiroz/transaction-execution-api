package com.aquiroz.transactions.config;

import com.aquiroz.transactions.domain.rule.DebitLimitRule;
import com.aquiroz.transactions.domain.rule.MinimumAmountRule;
import com.aquiroz.transactions.domain.rule.SupportedCurrencyRule;
import com.aquiroz.transactions.domain.rule.TransactionPolicy;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RulesProperties.class)
public class DomainConfig {

    @Bean
    public Clock clock() {
        return Clock.tickMillis(ZoneOffset.UTC);
    }

    @Bean
    public TransactionPolicy transactionPolicy(RulesProperties rules) {
        return new TransactionPolicy(List.of(
                new MinimumAmountRule(rules.minimumAmount()),
                new DebitLimitRule(rules.debitMaxAmount()),
                new SupportedCurrencyRule(rules.supportedCurrencies())));
    }
}
