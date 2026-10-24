package com.aquiroz.transactions.config;

import com.aquiroz.transactions.application.StalePendingSweeper;
import com.aquiroz.transactions.domain.port.TransactionRepository;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SweeperProperties.class)
public class SweeperConfig {

    @Bean
    public StalePendingSweeper stalePendingSweeper(TransactionRepository repository, Clock clock,
                                                   SweeperProperties properties) {
        return new StalePendingSweeper(repository, clock, properties.staleAfter());
    }
}
