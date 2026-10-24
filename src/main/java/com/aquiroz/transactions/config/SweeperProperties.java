package com.aquiroz.transactions.config;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * stale-after must be far longer than the longest possible request (timeouts plus one retry, about 5 s),
 * so the sweeper never races a request that is still alive.
 */
@ConfigurationProperties("transactions.sweeper")
public record SweeperProperties(Duration interval, Duration staleAfter) {

    public SweeperProperties {
        requirePositive(interval, "interval");
        requirePositive(staleAfter, "stale-after");
    }

    private static void requirePositive(Duration value, String property) {
        Objects.requireNonNull(value, property);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(property + " must be positive: " + value);
        }
    }
}
