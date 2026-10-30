package com.moveinsync.roster.api.dto;

import com.moveinsync.roster.repository.ReconciliationRun;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** What the operator sees. Employee IDs only. */
public record RunView(UUID runId, String status, List<String> brakeReasons, Instant asOf,
                      Integer expectedRows, int rowsRead, int rowsRejected, boolean truncated,
                      Integer fileAppearsShortBy, int activeBefore, int appearsMissing, Double missingRatio,
                      Double threshold, int toCreate, int toUpdate, int applied, int stale, int deactivated,
                      Long diffMillis, String errorCode, String decidedBy, Instant decidedAt,
                      List<String> sampleMissingEmployeeIds) {

    public static RunView of(ReconciliationRun r, List<String> sample) {
        Integer shortBy = r.expectedRows() == null ? null : Math.max(0, r.expectedRows() - r.rowsRead());
        List<String> reasons = r.brakeReasons() == null ? List.of() : Arrays.asList(r.brakeReasons().split(","));
        return new RunView(r.runId(), r.status(), reasons, r.asOf(), r.expectedRows(), r.rowsRead(), r.rowsRejected(),
                r.truncated(), shortBy, r.activeBefore(), r.toDeactivate(), r.missingRatio(), r.threshold(),
                r.toCreate(), r.toUpdate(), r.applied(), r.stale(), r.deactivated(), r.diffMillis(), r.errorCode(),
                r.decidedBy(), r.decidedAt(), sample);
    }
}
