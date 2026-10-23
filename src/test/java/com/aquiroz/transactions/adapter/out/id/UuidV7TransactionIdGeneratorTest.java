package com.aquiroz.transactions.adapter.out.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class UuidV7TransactionIdGeneratorTest {

    private final UuidV7TransactionIdGenerator generator = new UuidV7TransactionIdGenerator();

    @Test
    void shouldGenerateVersion7Uuid() {
        var id = generator.nextId();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void shouldGenerateIncreasingIdsEvenWithinSameMillisecond() {
        var ids = IntStream.range(0, 1_000).mapToObj(_ -> generator.nextId().toString()).toList();

        assertThat(ids).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void shouldEmbedCurrentTimeInMostSignificantBits() {
        long before = System.currentTimeMillis();
        UUID id = generator.nextId();
        long after = System.currentTimeMillis();

        long embeddedMillis = id.getMostSignificantBits() >>> 16;

        assertThat(embeddedMillis).isBetween(before, after);
    }
}
