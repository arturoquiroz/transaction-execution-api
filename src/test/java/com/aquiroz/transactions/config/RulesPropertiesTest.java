package com.aquiroz.transactions.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RulesPropertiesTest {

    @Test
    void shouldNormalizeLimitsToMoneyScale() {
        var properties = new RulesProperties(new BigDecimal("1"), new BigDecimal("10000"), Set.of("MXN"));

        assertThat(properties.minimumAmount()).isEqualTo(new BigDecimal("1.00"));
        assertThat(properties.debitMaxAmount()).isEqualTo(new BigDecimal("10000.00"));
    }

    @Test
    void shouldRejectMinimumNotLowerThanDebitMax() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RulesProperties(new BigDecimal("10000.00"), new BigDecimal("10000.00"),
                        Set.of("MXN")))
                .withMessageContaining("must be lower than");
    }

    @Test
    void shouldRejectLimitWithMoreThanTwoDecimals() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RulesProperties(new BigDecimal("1.005"), new BigDecimal("10000.00"),
                        Set.of("MXN")));
    }

    @Test
    void shouldRejectEmptySupportedCurrencies() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RulesProperties(new BigDecimal("1.00"), new BigDecimal("10000.00"), Set.of()));
    }
}
