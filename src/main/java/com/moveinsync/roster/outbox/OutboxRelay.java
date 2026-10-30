package com.moveinsync.roster.outbox;

import com.moveinsync.roster.config.RosterProperties;
import com.moveinsync.roster.fault.FaultInjector;
import com.moveinsync.roster.fault.FaultPoint;
import com.moveinsync.roster.observability.RosterMetrics;
import com.moveinsync.roster.repository.OutboxRepository;
import com.moveinsync.roster.repository.OutboxRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Moves committed outbox rows to the broker.
 *
 * <ul>
 *   <li>Single active relay across all instances (transaction-scoped advisory lock), so events
 *       go out in commit order and per-employee order is never broken by parallel relays.</li>
 *   <li>Head-of-line: if an event fails, the batch stops there and it is retried with exponential
 *       backoff; later events wait. Order over throughput.</li>
 *   <li>At-least-once: a crash after publishing but before marking means the event is sent again.
 *       Consumers dedupe by eventId.</li>
 * </ul>
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repo;
    private final ChangeEventPublisher publisher;
    private final FaultInjector faults;
    private final RosterMetrics metrics;
    private final TransactionTemplate tx;
    private final RosterProperties props;
    private final Clock clock;

    public OutboxRelay(OutboxRepository repo, ChangeEventPublisher publisher, FaultInjector faults,
                       RosterMetrics metrics, TransactionTemplate tx, RosterProperties props, Clock clock) {
        this.repo = repo;
        this.publisher = publisher;
        this.faults = faults;
        this.metrics = metrics;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${roster.outbox.poll-interval-ms:200}")
    public void scheduled() {
        if (!props.outbox().relayEnabled()) {
            return;
        }
        try {
            relayOnce();
        } catch (RuntimeException e) {
            log.error("outbox relay pass failed error={}", e.getClass().getSimpleName());
        }
    }

    /** @return number of events published in this pass */
    public int relayOnce() {
        Integer n = tx.execute(s -> {
            if (!repo.tryRelayLock()) {
                return 0;
            }
            List<OutboxRow> batch = repo.nextBatch(props.outbox().batchSize());
            if (batch.isEmpty()) {
                return 0;
            }
            Instant now = clock.instant();
            List<Long> published = new ArrayList<>(batch.size());
            faults.hit(FaultPoint.RELAY_BEFORE_PUBLISH);
            for (OutboxRow row : batch) {
                if (row.nextAttemptAt() != null && row.nextAttemptAt().isAfter(now)) {
                    break; // backing off; keep order
                }
                try {
                    publisher.publish(row);
                } catch (Exception e) {
                    Duration backoff = backoff(row.attempts() + 1);
                    repo.markFailed(row.id(), e.getClass().getSimpleName(), now.plus(backoff));
                    metrics.outboxFailure();
                    log.warn("publish failed eventId={} attempts={} retryIn={}ms error={}",
                            row.eventId(), row.attempts() + 1, backoff.toMillis(), e.getClass().getSimpleName());
                    break;
                }
                published.add(row.id());
            }
            faults.hit(FaultPoint.RELAY_AFTER_PUBLISH_BEFORE_MARK);
            repo.markPublished(published);
            return published.size();
        });
        int count = n == null ? 0 : n;
        metrics.outboxPublished(count);
        return count;
    }

    Duration backoff(int attempt) {
        long ms = 200L << Math.min(attempt, 16);
        return Duration.ofMillis(Math.min(ms, props.outbox().maxBackoff().toMillis()));
    }
}
