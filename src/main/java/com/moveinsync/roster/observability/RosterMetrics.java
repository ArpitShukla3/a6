package com.moveinsync.roster.observability;

import com.moveinsync.roster.domain.event.EventSource;
import com.moveinsync.roster.domain.policy.Outcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters an on-call engineer needs: ingest outcomes and rejections, lag, outbox queue depth
 * and age, publish failures/retries, reconciliation results and brake trips.
 * Exposed at /actuator/prometheus (dots become underscores).
 */
@Component
public class RosterMetrics {

    private final MeterRegistry registry;
    private final Timer applyTimer;
    private final Timer ingestLag;
    private final Counter ownershipViolations;
    private final Counter outboxPublished;
    private final Counter outboxFailures;
    private final Counter bookingsCancelled;
    private final Timer reconDiff;
    private final AtomicLong outboxPending = new AtomicLong();
    private final AtomicLong outboxOldestAgeSeconds = new AtomicLong();
    private final AtomicLong heldRuns = new AtomicLong();
    private final AtomicLong lastMissingRatioPpm = new AtomicLong();

    public RosterMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.applyTimer = Timer.builder("roster.ingest.apply").description("Time to apply one event")
                .publishPercentiles(0.5, 0.99).publishPercentileHistogram().register(registry);
        this.ingestLag = Timer.builder("roster.ingest.lag").description("Now minus source event time")
                .publishPercentiles(0.5, 0.99).register(registry);
        this.ownershipViolations = Counter.builder("roster.ownership.violations")
                .description("Transport-owned fields dropped from HR payloads").register(registry);
        this.outboxPublished = Counter.builder("roster.outbox.published").register(registry);
        this.outboxFailures = Counter.builder("roster.outbox.publish.failures")
                .description("Failed publish attempts; each one is retried with backoff").register(registry);
        this.bookingsCancelled = Counter.builder("roster.bookings.cancelled").register(registry);
        this.reconDiff = Timer.builder("roster.reconciliation.diff").publishPercentiles(0.5, 0.99).register(registry);
        Gauge.builder("roster.outbox.pending", outboxPending, AtomicLong::get)
                .description("Queue depth: change events not yet published").register(registry);
        Gauge.builder("roster.outbox.oldest.age.seconds", outboxOldestAgeSeconds, AtomicLong::get)
                .description("Publish lag: age of the oldest unpublished event").register(registry);
        Gauge.builder("roster.reconciliation.held.runs", heldRuns, AtomicLong::get)
                .description("Runs stopped by the safety brake, waiting for an operator").register(registry);
        Gauge.builder("roster.reconciliation.last.missing.ratio", lastMissingRatioPpm, v -> v.get() / 1_000_000.0)
                .register(registry);
    }

    public void ingestOutcome(Outcome outcome, EventSource source) {
        registry.counter("roster.ingest.events", "outcome", outcome.name(), "source", source.name()).increment();
    }

    public void rejection(String code, EventSource source) {
        registry.counter("roster.ingest.rejections", "code", code, "source", source.name()).increment();
    }

    public void applyDuration(Duration d) {
        applyTimer.record(d);
    }

    public void ingestLag(Duration d) {
        if (!d.isNegative()) {
            ingestLag.record(d);
        }
    }

    public void ownershipViolation() {
        ownershipViolations.increment();
    }

    public void changeEvent(String type) {
        registry.counter("roster.change.events", "type", type).increment();
    }

    public void outboxPublished(int n) {
        outboxPublished.increment(n);
    }

    public void outboxFailure() {
        outboxFailures.increment();
    }

    public void bookingsCancelled(int n) {
        bookingsCancelled.increment(n);
    }

    public void reconciliationRun(String status) {
        registry.counter("roster.reconciliation.runs", "status", status).increment();
    }

    public void brakeTripped(String reason) {
        registry.counter("roster.reconciliation.brake.tripped", "reason", reason).increment();
    }

    public void reconciliationDiff(Duration d, double missingRatio) {
        reconDiff.record(d);
        lastMissingRatioPpm.set(Math.round(missingRatio * 1_000_000));
    }

    public void outboxState(long pending, long oldestAgeSeconds) {
        outboxPending.set(pending);
        outboxOldestAgeSeconds.set(oldestAgeSeconds);
    }

    public void heldRuns(long n) {
        heldRuns.set(n);
    }
}
