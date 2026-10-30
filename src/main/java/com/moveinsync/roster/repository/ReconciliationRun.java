package com.moveinsync.roster.repository;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationRun(UUID runId, UUID tenantId, String status, String filePath, Instant asOf,
                                Integer expectedRows, int rowsRead, int rowsRejected, boolean truncated,
                                int activeBefore, int toCreate, int toUpdate, int toDeactivate,
                                Double missingRatio, Double threshold, String brakeReasons,
                                int applied, int stale, int deactivated, Long diffMillis, String errorCode,
                                String decidedBy, Instant decidedAt, Instant createdAt) {
}
