package com.aquiroz.transactions.config;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SweeperPropertiesTest {

    @Test
    void shouldRejectNonPositiveDurations() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SweeperProperties(Duration.ZERO, Duration.ofMinutes(5)))
                .withMessageContaining("interval");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SweeperProperties(Duration.ofSeconds(60), Duration.ofSeconds(-1)))
                .withMessageContaining("stale-after");
    }
}
