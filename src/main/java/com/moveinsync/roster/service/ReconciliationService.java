package com.moveinsync.roster.service;

import com.moveinsync.roster.config.RosterProperties;
import com.moveinsync.roster.domain.event.EventSource;
import com.moveinsync.roster.domain.event.SnapshotRowEvent;
import com.moveinsync.roster.domain.model.RecordHasher;
import com.moveinsync.roster.domain.model.TenantId;
import com.moveinsync.roster.domain.policy.Outcome;
import com.moveinsync.roster.domain.reconcile.BrakeDecision;
import com.moveinsync.roster.domain.reconcile.BrakeInput;
import com.moveinsync.roster.domain.reconcile.BucketDigests;
import com.moveinsync.roster.domain.reconcile.BucketSpill;
import com.moveinsync.roster.domain.reconcile.BucketedSnapshotDiffer;
import com.moveinsync.roster.domain.reconcile.DiffSink;
import com.moveinsync.roster.domain.reconcile.DiffStats;
import com.moveinsync.roster.domain.reconcile.SafetyBrake;
import com.moveinsync.roster.domain.reconcile.SnapshotFormatException;
import com.moveinsync.roster.domain.reconcile.SnapshotReader;
import com.moveinsync.roster.domain.reconcile.SnapshotScan;
import com.moveinsync.roster.domain.reconcile.SpilledRow;
import com.moveinsync.roster.domain.reconcile.StoreView;
import com.moveinsync.roster.domain.reconcile.StoredRow;
import com.moveinsync.roster.domain.validation.ErrorCodes;
import com.moveinsync.roster.domain.validation.FieldError;
import com.moveinsync.roster.domain.validation.IncomingRecord;
import com.moveinsync.roster.domain.validation.RecordType;
import com.moveinsync.roster.domain.validation.RecordValidationChain;
import com.moveinsync.roster.domain.validation.ValidationContext;
import com.moveinsync.roster.error.ConflictException;
import com.moveinsync.roster.error.NotFoundException;
import com.moveinsync.roster.fault.FaultInjector;
import com.moveinsync.roster.fault.FaultPoint;
import com.moveinsync.roster.observability.RosterMetrics;
import com.moveinsync.roster.repository.JdbcStoreView;
import com.moveinsync.roster.repository.OfficeRepository;
import com.moveinsync.roster.repository.ReconciliationRun;
import com.moveinsync.roster.repository.ReconciliationRunRepository;
import com.moveinsync.roster.repository.RejectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Nightly reconciliation.
 *
 * <pre>
 * RECEIVED -> DIFFING -> (brake tripped) HELD -> approve -> APPLYING -> COMPLETED
 *                                              -> reject  -> REJECTED
 *                     -> (brake ok)      APPLYING -> COMPLETED
 * any phase -> FAILED (operator can retry)
 * </pre>
 *
 * Diff phase: stream the file once into hash buckets on disk, compare per-bucket digests with
 * the store, drill into differing buckets only, count changes and record deactivation candidates.
 * Nothing is written to employees in this phase. Apply phase: re-read the file, re-diff (so it
 * sees any real-time changes that happened meanwhile) and apply changes through the same
 * per-record path as the API; deactivate only approved candidates that are still unchanged.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final int MAX_STORED_REJECTIONS = 10_000;
    private static final int CANDIDATE_FLUSH = 5_000;

    private final RosterProperties props;
    private final ReconciliationRunRepository runs;
    private final RejectionRepository rejections;
    private final OfficeRepository offices;
    private final RecordValidationChain validation;
    private final RecordHasher hasher;
    private final SnapshotReader reader;
    private final BucketedSnapshotDiffer differ;
    private final SafetyBrake brake;
    private final RosterIngestService ingest;
    private final FaultInjector faults;
    private final RosterMetrics metrics;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate readTx;
    private final TaskExecutor executor;

    public ReconciliationService(RosterProperties props, ReconciliationRunRepository runs, RejectionRepository rejections,
                                 OfficeRepository offices, RecordValidationChain validation, RecordHasher hasher,
                                 SnapshotReader reader, BucketedSnapshotDiffer differ, SafetyBrake brake,
                                 RosterIngestService ingest, FaultInjector faults, RosterMetrics metrics,
                                 JdbcTemplate jdbc, PlatformTransactionManager txm, TaskExecutor reconciliationExecutor) {
        this.props = props;
        this.runs = runs;
        this.rejections = rejections;
        this.offices = offices;
        this.validation = validation;
        this.hasher = hasher;
        this.reader = reader;
        this.differ = differ;
        this.brake = brake;
        this.ingest = ingest;
        this.faults = faults;
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.readTx = new TransactionTemplate(txm);
        this.readTx.setReadOnly(true);
        this.executor = reconciliationExecutor;
    }

    // ---------------------------------------------------------------- API entry points

    /** Stores the upload on disk (streamed, never fully in memory) and starts the run. */
    public ReconciliationRun submit(TenantId tenant, InputStream body) {
        UUID runId = UUID.randomUUID();
        Path file = dataDir().resolve(runId + ".csv");
        try {
            Files.createDirectories(file.getParent());
            Files.copy(body, file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            runs.insert(runId, tenant, file.toString());
        } catch (DuplicateKeyException e) {
            deleteQuietly(file);
            throw new ConflictException("RUN_IN_FLIGHT", "A reconciliation run is already in progress for this tenant");
        }
        metrics.reconciliationRun("RECEIVED");
        log.info("snapshot received runId={} tenantId={}", runId, tenant);
        schedule(() -> process(runId));
        return runs.findInternal(runId).orElseThrow();
    }

    public ReconciliationRun approve(TenantId tenant, UUID runId, String operator) {
        ReconciliationRun run = find(tenant, runId);
        boolean moved;
        try {
            moved = runs.transition(runId, "HELD", "APPLYING");
        } catch (DuplicateKeyException e) {
            throw new ConflictException("RUN_IN_FLIGHT", "Another run is in progress for this tenant");
        }
        if (!moved) {
            throw new ConflictException("RUN_NOT_HELD", "Only a held run can be approved");
        }
        runs.decide(runId, operator);
        log.warn("held run approved runId={} operator={} toDeactivate={}", runId, operator, run.toDeactivate());
        schedule(() -> apply(runId));
        return runs.findInternal(runId).orElseThrow();
    }

    public ReconciliationRun reject(TenantId tenant, UUID runId, String operator) {
        ReconciliationRun run = find(tenant, runId);
        if (!runs.transition(runId, "HELD", "REJECTED")) {
            throw new ConflictException("RUN_NOT_HELD", "Only a held run can be rejected");
        }
        runs.decide(runId, operator);
        deleteQuietly(Paths.get(run.filePath()));
        metrics.reconciliationRun("REJECTED");
        log.info("held run rejected runId={} operator={}", runId, operator);
        return runs.findInternal(runId).orElseThrow();
    }

    /** Resume a FAILED run: from the apply phase if the diff had finished, else from the start. */
    public ReconciliationRun retry(TenantId tenant, UUID runId, String operator) {
        ReconciliationRun run = find(tenant, runId);
        boolean diffDone = run.diffMillis() != null && (run.brakeReasons() == null || run.decidedBy() != null);
        String to = diffDone ? "APPLYING" : "RECEIVED";
        try {
            if (!runs.transition(runId, "FAILED", to)) {
                throw new ConflictException("RUN_NOT_FAILED", "Only a failed run can be retried");
            }
        } catch (DuplicateKeyException e) {
            throw new ConflictException("RUN_IN_FLIGHT", "Another run is in progress for this tenant");
        }
        log.info("run retried runId={} operator={} phase={}", runId, operator, to);
        schedule(diffDone ? () -> apply(runId) : () -> process(runId));
        return runs.findInternal(runId).orElseThrow();
    }

    public ReconciliationRun find(TenantId tenant, UUID runId) {
        return runs.find(tenant, runId).orElseThrow(() -> new NotFoundException("RUN_NOT_FOUND", "Run not found"));
    }

    public List<String> sampleMissing(UUID runId) {
        return runs.sampleCandidateExternalIds(runId, 20);
    }

    /** A process restart must not strand runs: resume whatever was in flight. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInFlightRuns() {
        for (ReconciliationRun r : runs.findByStatus(List.of("RECEIVED", "DIFFING", "APPLYING"))) {
            log.warn("resuming run after restart runId={} status={}", r.runId(), r.status());
            if ("APPLYING".equals(r.status())) {
                schedule(() -> apply(r.runId()));
            } else {
                runs.transition(r.runId(), "DIFFING", "RECEIVED");
                schedule(() -> process(r.runId()));
            }
        }
    }

    // ---------------------------------------------------------------- phases

    void process(UUID runId) {
        if (!runs.transition(runId, "RECEIVED", "DIFFING")) {
            return;
        }
        ReconciliationRun run = runs.findInternal(runId).orElseThrow();
        TenantId tenant = new TenantId(run.tenantId());
        try {
            Instant started = Instant.now();
            Path spillDir = dataDir().resolve(runId + "-spill");
            DiffStats stats;
            SpillOutcome spilled;
            int activeBefore;
            try (BucketSpill spill = new BucketSpill(spillDir, hasher)) {
                spilled = spill(run, tenant, spill, true);
                StoreView store = storeView(tenant);
                BucketDigests storeDigests = store.activeDigests();
                activeBefore = storeDigests.total();
                List<Integer> differing = differ.differingBuckets(spill.digests(), storeDigests);
                CandidateCollector sink = new CandidateCollector(runId, tenant);
                runs.clearCandidates(runId);
                stats = differ.drill(differing, spill, store, spilled.scan().header().asOf(), sink);
                sink.flush();
                spilled.rejected().addAndGet(sink.duplicates);
            }
            Duration took = Duration.between(started, Instant.now());
            double threshold = offices.brakeMissingRatio(tenant).orElse(props.reconciliation().brakeMissingRatio());
            BrakeInput in = new BrakeInput(activeBefore, stats.missing(), spilled.scan().rowsRead(),
                    spilled.rejected().get(), spilled.scan().truncated(), threshold, props.reconciliation().brakeRejectedRatio());
            BrakeDecision decision = brake.evaluate(in);
            runs.saveDiff(runId, spilled.scan().header().asOf(), spilled.scan().header().expectedRows(),
                    spilled.scan().rowsRead(), spilled.rejected().get(), spilled.scan().truncated(), activeBefore,
                    stats.newRows(), stats.changedRows(), stats.missing(), decision.missingRatio(), threshold,
                    decision.tripped() ? String.join(",", decision.reasons()) : null, took.toMillis());
            metrics.reconciliationDiff(took, decision.missingRatio());
            log.info("snapshot diffed runId={} rows={} rejected={} new={} changed={} missing={} bucketsDiffering={} millis={}",
                    runId, spilled.scan().rowsRead(), spilled.rejected().get(), stats.newRows(), stats.changedRows(),
                    stats.missing(), stats.bucketsDiffering(), took.toMillis());

            if (decision.tripped()) {
                runs.transition(runId, "DIFFING", "HELD");
                metrics.reconciliationRun("HELD");
                decision.reasons().forEach(metrics::brakeTripped);
                // The operator alert. Prometheus alerts on roster_reconciliation_brake_tripped_total / held_runs.
                log.warn("SAFETY BRAKE TRIPPED runId={} tenantId={} reasons={} activeBefore={} rowsRead={} expectedRows={} "
                                + "appearsMissing={} missingRatio={} threshold={} - nobody deactivated, operator approval required",
                        runId, tenant, decision.reasons(), activeBefore, spilled.scan().rowsRead(),
                        spilled.scan().header().expectedRows(), stats.missing(), decision.missingRatio(), threshold);
                return;
            }
            runs.transition(runId, "DIFFING", "APPLYING");
            apply(runId);
        } catch (SnapshotFormatException e) {
            runs.fail(runId, e.code());
            metrics.reconciliationRun("FAILED");
            log.warn("snapshot rejected runId={} code={}", runId, e.code());
        } catch (RuntimeException e) {
            runs.fail(runId, "INTERNAL_ERROR");
            metrics.reconciliationRun("FAILED");
            log.error("reconciliation diff failed runId={} error={}", runId, e.getClass().getSimpleName());
        }
    }

    void apply(UUID runId) {
        ReconciliationRun run = runs.findInternal(runId).orElseThrow();
        if (!"APPLYING".equals(run.status())) {
            return;
        }
        TenantId tenant = new TenantId(run.tenantId());
        try {
            ApplyingSink sink;
            try (BucketSpill spill = new BucketSpill(dataDir().resolve(runId + "-apply"), hasher)) {
                SpillOutcome spilled = spill(run, tenant, spill, false);
                StoreView store = storeView(tenant);
                List<Integer> differing = differ.differingBuckets(spill.digests(), store.activeDigests());
                sink = new ApplyingSink(runId, tenant, spilled.scan().header().asOf());
                differ.drill(differing, spill, store, spilled.scan().header().asOf(), sink);
            }
            runs.saveApplied(runId, sink.applied, sink.stale, sink.deactivated);
            runs.transition(runId, "APPLYING", "COMPLETED");
            deleteQuietly(Paths.get(run.filePath()));
            metrics.reconciliationRun("COMPLETED");
            log.info("reconciliation completed runId={} applied={} stale={} conflicts={} deactivated={}",
                    runId, sink.applied, sink.stale, sink.conflicts, sink.deactivated);
        } catch (SnapshotFormatException e) {
            runs.fail(runId, e.code());
            metrics.reconciliationRun("FAILED");
        } catch (RuntimeException e) {
            runs.fail(runId, "APPLY_FAILED");
            metrics.reconciliationRun("FAILED");
            log.error("reconciliation apply failed runId={} error={} (retry is safe: apply is idempotent)",
                    runId, e.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------- pass 1: stream + validate + spill

    private record SpillOutcome(SnapshotScan scan, AtomicInteger rejected) {
    }

    private SpillOutcome spill(ReconciliationRun run, TenantId tenant, BucketSpill spill, boolean recordRejections) {
        ValidationContext ctx = new ValidationContext(Set.copyOf(offices.codes(tenant)));
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger stored = new AtomicInteger();
        try (BufferedReader in = Files.newBufferedReader(Paths.get(run.filePath()), StandardCharsets.UTF_8)) {
            SnapshotScan scan = reader.read(in, (index, f) -> {
                if (f == null) {
                    rejected.incrementAndGet();
                    if (recordRejections) reject(tenant, run.runId(), index, null, ErrorCodes.MALFORMED_ROW, "record", stored);
                    return;
                }
                String ext = f.get(0).trim();
                Long version = parseLong(f.get(1));
                IncomingRecord rec = new IncomingRecord(RecordType.SNAPSHOT, ext, version, null,
                        f.get(2), f.get(3), f.get(4), f.get(5), f.get(6), f.get(7), Set.of());
                List<FieldError> errors = new ArrayList<>(validation.validate(rec, ctx));
                if (version == null && !f.get(1).isBlank()) {
                    errors.removeIf(e -> e.field().equals("sourceVersion"));
                    errors.add(new FieldError("sourceVersion", ErrorCodes.VERSION_INVALID));
                }
                if (!errors.isEmpty()) {
                    rejected.incrementAndGet();
                    boolean idReadable = errors.stream().noneMatch(e -> e.field().equals("externalId"));
                    if (recordRejections) {
                        for (FieldError e : errors) {
                            metrics.rejection(e.code(), EventSource.SNAPSHOT);
                            reject(tenant, run.runId(), index, idReadable ? ext : null, e.code(), e.field(), stored);
                        }
                    }
                    // A bad row with a readable ID means the employee is present: never "missing".
                    if (idReadable) {
                        spill.add(SpilledRow.rejected(index, ext));
                    }
                    return;
                }
                var hr = validation.normalize(rec);
                spill.add(new SpilledRow(index, ext, version, hasher.digest(ext, hr), false, hr));
            });
            spill.finishWriting();
            return new SpillOutcome(scan, rejected);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void reject(TenantId tenant, UUID runId, int index, String ext, String code, String field, AtomicInteger stored) {
        if (stored.incrementAndGet() <= MAX_STORED_REJECTIONS) {
            rejections.insert(tenant, "SNAPSHOT", runId, index, ext, field, code);
        }
    }

    // ---------------------------------------------------------------- sinks

    /** Diff phase: records who would be deactivated; applies nothing. */
    private final class CandidateCollector implements DiffSink {
        private final UUID runId;
        private final TenantId tenant;
        private final List<UUID> ids = new ArrayList<>();
        private final List<Long> versions = new ArrayList<>();
        int duplicates;

        CandidateCollector(UUID runId, TenantId tenant) {
            this.runId = runId;
            this.tenant = tenant;
        }

        @Override
        public void onNew(SpilledRow row) {
        }

        @Override
        public void onChanged(SpilledRow row, StoredRow stored) {
        }

        @Override
        public void onMissing(StoredRow stored) {
            ids.add(stored.employeeId());
            versions.add(stored.hrVersion());
            if (ids.size() >= CANDIDATE_FLUSH) {
                flush();
            }
        }

        @Override
        public void onDuplicateRow(SpilledRow row) {
            duplicates++;
            rejections.insert(tenant, "SNAPSHOT", runId, row.index(), row.externalId(), "externalId", ErrorCodes.DUPLICATE_ROW);
        }

        void flush() {
            if (!ids.isEmpty()) {
                runs.insertCandidates(runId, ids, versions);
                ids.clear();
                versions.clear();
            }
        }
    }

    /** Apply phase: changes go through the normal versioned path; deactivations only if approved. */
    private final class ApplyingSink implements DiffSink {
        private final UUID runId;
        private final TenantId tenant;
        private final Instant asOf;
        int applied, stale, conflicts, deactivated;

        ApplyingSink(UUID runId, TenantId tenant, Instant asOf) {
            this.runId = runId;
            this.tenant = tenant;
            this.asOf = asOf;
        }

        @Override
        public void onNew(SpilledRow row) {
            change(row);
        }

        @Override
        public void onChanged(SpilledRow row, StoredRow stored) {
            change(row);
        }

        private void change(SpilledRow row) {
            faults.hit(FaultPoint.RECON_APPLY_STEP);
            Outcome o = ingest.apply(tenant, new SnapshotRowEvent(row.externalId(), row.version(), asOf, row.hr())).outcome();
            switch (o) {
                case APPLIED -> applied++;
                case STALE, DUPLICATE -> stale++;
                case CONFLICT -> conflicts++;
                default -> { }
            }
        }

        @Override
        public void onMissing(StoredRow stored) {
            faults.hit(FaultPoint.RECON_APPLY_STEP);
            // Only employees recorded as candidates at diff time (and so covered by the brake /
            // the operator's approval) can be deactivated, and only if unchanged since.
            runs.candidateVersion(runId, stored.employeeId()).ifPresent(v -> {
                if (ingest.deactivateByReconciliation(tenant, stored.employeeId(), v, asOf)) {
                    deactivated++;
                }
            });
        }
    }

    // ---------------------------------------------------------------- helpers

    private StoreView storeView(TenantId tenant) {
        return new JdbcStoreView(jdbc, readTx, tenant, hasher.bucketCount());
    }

    private void schedule(Runnable r) {
        if (props.reconciliation().async()) {
            executor.execute(r);
        } else {
            r.run();
        }
    }

    private Path dataDir() {
        return Paths.get(props.reconciliation().dataDir());
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // temp file; cleaned up on next start at worst
        }
    }
}
