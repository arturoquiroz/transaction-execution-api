package com.aquiroz.transactions.application;

import com.aquiroz.transactions.domain.port.TransactionRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * Moves transactions left PENDING by a crash or a failed final UPDATE to UNKNOWN, so none stays unreviewed (D15).
 * UNKNOWN and not FAILED: the provider may have executed them, and nothing in our data tells otherwise.
 */
@Slf4j
public class StalePendingSweeper {

    private final TransactionRepository repository;
    private final Clock clock;
    private final Duration staleAfter;

    public StalePendingSweeper(TransactionRepository repository, Clock clock, Duration staleAfter) {
        this.repository = repository;
        this.clock = clock;
        this.staleAfter = Objects.requireNonNull(staleAfter, "staleAfter");
    }

    public int sweep() {
        var now = clock.instant();
        int moved = repository.markStalePendingAsUnknown(now.minus(staleAfter), now);
        if (moved > 0) {
            log.warn("Moved {} stale PENDING transaction(s) to UNKNOWN; they need reconciliation", moved);
        }
        return moved;
    }
}
