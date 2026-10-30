package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.event.RosterEvent;
import com.moveinsync.roster.domain.model.EmployeeState;
import com.moveinsync.roster.domain.model.TenantId;
import com.moveinsync.roster.domain.policy.ChangeKind;
import com.moveinsync.roster.domain.policy.Decision;
import com.moveinsync.roster.domain.policy.EmployeeMerger;
import com.moveinsync.roster.domain.policy.MergeResult;
import com.moveinsync.roster.domain.policy.VersionGate;
import com.moveinsync.roster.fault.FaultInjector;
import com.moveinsync.roster.fault.FaultPoint;
import com.moveinsync.roster.observability.RosterMetrics;
import com.moveinsync.roster.repository.EmployeeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies one HR event to one employee. Each call is its own transaction:
 * lock row &rarr; decide (VersionGate) &rarr; merge (EmployeeMerger) &rarr; write row &rarr;
 * write outbox event &rarr; deactivation side effects. All or nothing.
 */
@Service
public class RosterIngestService {

    private static final Logger log = LoggerFactory.getLogger(RosterIngestService.class);

    private final EmployeeRepository employees;
    private final VersionGate gate;
    private final EmployeeMerger merger;
    private final OutboxWriter outbox;
    private final DeactivationSideEffects sideEffects;
    private final FaultInjector faults;
    private final RosterMetrics metrics;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RosterIngestService(EmployeeRepository employees, VersionGate gate, EmployeeMerger merger,
                               OutboxWriter outbox, DeactivationSideEffects sideEffects, FaultInjector faults,
                               RosterMetrics metrics, TransactionTemplate tx, Clock clock) {
        this.employees = employees;
        this.gate = gate;
        this.merger = merger;
        this.outbox = outbox;
        this.sideEffects = sideEffects;
        this.faults = faults;
        this.metrics = metrics;
        this.tx = tx;
        this.clock = clock;
    }

    public ApplyResult apply(TenantId tenant, RosterEvent event) {
        Instant start = clock.instant();
        try {
            // Second attempt only happens if a concurrent transaction inserted the same new employee.
            for (int attempt = 0; attempt < 2; attempt++) {
                ApplyResult r = tx.execute(s -> applyLocked(tenant, event));
                if (r != null) {
                    metrics.ingestOutcome(r.outcome(), event.source());
                    return r;
                }
            }
            throw new IllegalStateException("concurrent insert not resolved");
        } finally {
            metrics.applyDuration(Duration.between(start, clock.instant()));
        }
    }

    private ApplyResult applyLocked(TenantId tenant, RosterEvent event) {
        EmployeeState current = employees.lockByExternalId(tenant, event.externalId()).orElse(null);
        Decision decision = gate.decide(current, event);
        if (!decision.shouldApply()) {
            log.debug("event not applied ext={} v={} outcome={}", event.externalId(), event.version(), decision.outcome());
            return new ApplyResult(decision.outcome(), current == null ? null : current.employeeId(), null);
        }
        MergeResult m = merger.merge(tenant, current, event, decision.transition());
        if (current == null) {
            if (!employees.insertIfAbsent(m.state())) {
                return null; // lost an insert race; retry will take the lock path
            }
        } else {
            employees.update(m.state());
        }
        faults.hit(FaultPoint.INGEST_AFTER_EMPLOYEE_WRITE);

        EmployeeState s = m.state();
        outbox.write(tenant, s.employeeId(), "EMPLOYEE_" + m.kind().name(),
                body(s, m.changedFields(), event.source().name()));
        if (s.pickupNeedsReview() && m.changedFields().contains("pickupNeedsReview")) {
            outbox.write(tenant, s.employeeId(), "PICKUP_REVIEW_REQUIRED",
                    Map.of("externalId", s.externalId(), "reason", "OFFICE_CHANGED"));
        }
        if (m.kind() == ChangeKind.DEACTIVATED && current != null) {
            sideEffects.onDeactivated(tenant, s.employeeId());
        }
        log.info("event applied ext={} employeeId={} v={} kind={}", s.externalId(), s.employeeId(), s.hrVersion(), m.kind());
        return new ApplyResult(decision.outcome(), s.employeeId(), m.kind());
    }

    /**
     * Deactivation decided by the nightly reconciliation. Re-checks under the row lock that the
     * employee is still active, unchanged since the diff, and last changed before the export's
     * as-of time. The HR version is not bumped (it belongs to the HR system).
     *
     * @return true if the employee was deactivated now
     */
    public boolean deactivateByReconciliation(TenantId tenant, UUID employeeId, long versionAtDiff, Instant asOf) {
        Boolean done = tx.execute(st -> {
            Optional<EmployeeState> locked = employees.lockById(tenant, employeeId);
            if (locked.isEmpty()) {
                return false;
            }
            EmployeeState cur = locked.get();
            if (!cur.isActive() || cur.hrVersion() != versionAtDiff || !cur.hrSourceTs().isBefore(asOf)) {
                return false;
            }
            EmployeeState next = merger.deactivateByReconciliation(cur);
            employees.update(next);
            Map<String, Object> body = body(next, List.of("status"), "RECONCILIATION");
            outbox.write(tenant, next.employeeId(), "EMPLOYEE_DEACTIVATED", body);
            sideEffects.onDeactivated(tenant, next.employeeId());
            log.info("employee deactivated by reconciliation employeeId={} ext={}", next.employeeId(), next.externalId());
            return true;
        });
        return Boolean.TRUE.equals(done);
    }

    private static Map<String, Object> body(EmployeeState s, List<String> changed, String source) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("externalId", s.externalId());
        b.put("hrVersion", s.hrVersion());
        b.put("status", s.status().name());
        b.put("deactivatedBy", s.deactivatedBy() == null ? null : s.deactivatedBy().name());
        b.put("changedFields", changed);
        b.put("source", source);
        return b;
    }

}
