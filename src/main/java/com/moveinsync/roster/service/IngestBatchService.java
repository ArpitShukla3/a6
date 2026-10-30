package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.event.CreateEmployeeEvent;
import com.moveinsync.roster.domain.event.DeactivateEmployeeEvent;
import com.moveinsync.roster.domain.event.EventSource;
import com.moveinsync.roster.domain.event.RosterEvent;
import com.moveinsync.roster.domain.event.UpdateEmployeeEvent;
import com.moveinsync.roster.domain.model.TenantId;
import com.moveinsync.roster.domain.policy.FieldOwnershipPolicy;
import com.moveinsync.roster.domain.policy.Outcome;
import com.moveinsync.roster.domain.validation.ErrorCodes;
import com.moveinsync.roster.domain.validation.FieldError;
import com.moveinsync.roster.domain.validation.IncomingRecord;
import com.moveinsync.roster.domain.validation.RecordValidationChain;
import com.moveinsync.roster.domain.validation.ValidationContext;
import com.moveinsync.roster.observability.RosterMetrics;
import com.moveinsync.roster.repository.RejectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Real-time batch ingest. Validates every record at the boundary, then applies each valid one in
 * its own transaction. One bad record, or one failing record, never affects the others.
 */
@Service
public class IngestBatchService {

    private static final Logger log = LoggerFactory.getLogger(IngestBatchService.class);
    public static final int MAX_BATCH = 1000;

    private final RecordValidationChain validation;
    private final FieldOwnershipPolicy ownership;
    private final OfficeDirectory offices;
    private final RosterIngestService ingest;
    private final RejectionRepository rejections;
    private final RosterMetrics metrics;
    private final Clock clock;

    public IngestBatchService(RecordValidationChain validation, FieldOwnershipPolicy ownership, OfficeDirectory offices,
                              RosterIngestService ingest, RejectionRepository rejections, RosterMetrics metrics, Clock clock) {
        this.validation = validation;
        this.ownership = ownership;
        this.offices = offices;
        this.ingest = ingest;
        this.rejections = rejections;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** @param records null entries are records that could not even be parsed */
    public BatchResult ingest(TenantId tenant, UUID batchId, List<IncomingRecord> records) {
        ValidationContext ctx = new ValidationContext(offices.codes(tenant));
        List<RecordResult> results = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            results.add(one(tenant, batchId, i, records.get(i), ctx));
        }
        BatchResult result = BatchResult.of(batchId, results);
        log.info("batch ingested batchId={} records={} summary={}", batchId, records.size(), result.summary());
        return result;
    }

    private RecordResult one(TenantId tenant, UUID batchId, int index, IncomingRecord rec, ValidationContext ctx) {
        if (rec == null) {
            return reject(tenant, batchId, index, null, List.of(new FieldError("record", ErrorCodes.MALFORMED_ROW)), List.of());
        }
        List<FieldError> errors = validation.validate(rec, ctx);
        List<FieldError> warnings = ownership.violationsInHrPayload(rec.presentTransportFields()).stream()
                .map(f -> new FieldError(f, ErrorCodes.FIELD_OWNED_BY_TRANSPORT)).toList();
        warnings.forEach(w -> metrics.ownershipViolation());
        if (!errors.isEmpty()) {
            return reject(tenant, batchId, index, rec.externalId(), errors, warnings);
        }
        RosterEvent event = toEvent(rec);
        try {
            metrics.ingestLag(Duration.between(event.occurredAt(), clock.instant()));
            ApplyResult r = ingest.apply(tenant, event);
            if (r.outcome() == Outcome.CONFLICT) {
                List<FieldError> conflict = List.of(new FieldError("sourceVersion", ErrorCodes.VERSION_CONFLICT));
                recordRejections(tenant, batchId, index, rec.externalId(), conflict);
                return new RecordResult(index, rec.externalId(), Outcome.CONFLICT, conflict, warnings);
            }
            return new RecordResult(index, rec.externalId(), r.outcome(), List.of(), warnings);
        } catch (RuntimeException e) {
            // Only the exception type is logged: driver messages can contain submitted values.
            log.error("record failed batchId={} index={} ext={} error={}", batchId, index, rec.externalId(), e.getClass().getSimpleName());
            metrics.ingestOutcome(Outcome.FAILED, EventSource.API);
            return new RecordResult(index, rec.externalId(), Outcome.FAILED,
                    List.of(new FieldError("record", "TRANSIENT_ERROR")), warnings);
        }
    }

    private RosterEvent toEvent(IncomingRecord rec) {
        return switch (rec.type()) {
            case CREATE -> new CreateEmployeeEvent(rec.externalId(), rec.version(), rec.occurredAt(), validation.normalize(rec));
            case UPDATE -> new UpdateEmployeeEvent(rec.externalId(), rec.version(), rec.occurredAt(), validation.normalize(rec));
            case DEACTIVATE -> new DeactivateEmployeeEvent(rec.externalId(), rec.version(), rec.occurredAt());
            case SNAPSHOT -> throw new IllegalArgumentException("snapshot rows are not accepted here");
        };
    }

    private RecordResult reject(TenantId tenant, UUID batchId, int index, String ext,
                                List<FieldError> errors, List<FieldError> warnings) {
        errors.forEach(e -> metrics.rejection(e.code(), EventSource.API));
        metrics.ingestOutcome(Outcome.REJECTED, EventSource.API);
        recordRejections(tenant, batchId, index, ext, errors);
        return new RecordResult(index, ext, Outcome.REJECTED, errors, warnings);
    }

    private void recordRejections(TenantId tenant, UUID batchId, int index, String ext, List<FieldError> errors) {
        String safeExt = ext != null && ext.length() <= 64 ? ext : null;
        try {
            for (FieldError e : errors) {
                rejections.insert(tenant, "BATCH", batchId, index, safeExt, e.field(), e.code());
            }
        } catch (RuntimeException e) {
            log.warn("could not store rejection report batchId={} index={} error={}", batchId, index, e.getClass().getSimpleName());
        }
    }

}
