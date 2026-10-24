package com.aquiroz.transactions.domain.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PageQueryTest {

    @ParameterizedTest
    @CsvSource({"0, 1", "0, 100", "5, 20"})
    void shouldAcceptPageAndLimitWithinBounds(int page, int limit) {
        assertThat(new PageQuery(page, limit).limit()).isEqualTo(limit);
    }

    @ParameterizedTest
    @CsvSource({"-1, 20", "0, 0", "0, 101"})
    void shouldRejectPageOrLimitOutOfBounds(int page, int limit) {
        assertThatIllegalArgumentException().isThrownBy(() -> new PageQuery(page, limit));
    }

    @Test
    void shouldComputeOffsetWithoutIntOverflow() {
        assertThat(new PageQuery(Integer.MAX_VALUE, 100).offset()).isEqualTo(214_748_364_700L);
    }
}
