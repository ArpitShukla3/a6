package com.moveinsync.roster.outbox;

import com.moveinsync.roster.observability.RosterMetrics;
import com.moveinsync.roster.repository.OutboxRepository;
import com.moveinsync.roster.repository.ReconciliationRunRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/** Refreshes queue-depth and lag gauges every few seconds (cheap: partial index on unpublished rows). */
@Component
public class OutboxMonitor {

    private final OutboxRepository outbox;
    private final ReconciliationRunRepository runs;
    private final RosterMetrics metrics;
    private final Clock clock;

    public OutboxMonitor(OutboxRepository outbox, ReconciliationRunRepository runs, RosterMetrics metrics, Clock clock) {
        this.outbox = outbox;
        this.runs = runs;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${roster.monitor.interval-ms:5000}")
    public void refresh() {
        try {
            long pending = outbox.pendingCount();
            long age = outbox.oldestPendingCreatedAt()
                    .map(t -> Math.max(0, Duration.between(t, clock.instant()).toSeconds())).orElse(0L);
            metrics.outboxState(pending, age);
            metrics.heldRuns(runs.countByStatus("HELD"));
        } catch (RuntimeException ignored) {
            // database down: health check reports it; keep last values
        }
    }
}
