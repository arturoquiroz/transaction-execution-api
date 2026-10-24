package com.aquiroz.transactions.adapter.in.scheduling;

import com.aquiroz.transactions.application.StalePendingSweeper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Driving adapter: time is the trigger, as HTTP is for the controller, so the use case knows nothing about it.
 * Every instance runs it, which is safe because the conditional UPDATE is idempotent. Tests switch it off.
 */
@Component
@ConditionalOnProperty(name = "transactions.sweeper.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class StalePendingSweeperSchedule {

    private final StalePendingSweeper sweeper;

    @Scheduled(fixedDelayString = "${transactions.sweeper.interval}",
            initialDelayString = "${transactions.sweeper.interval}")
    public void run() {
        sweeper.sweep();
    }
}
