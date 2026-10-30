package com.moveinsync.roster.domain.reconcile;

import com.moveinsync.roster.domain.model.DeactivatedBy;
import com.moveinsync.roster.domain.model.EmployeeStatus;

import java.time.Instant;
import java.util.UUID;

/** The parts of a stored employee the diff needs. No personal data. */
public record StoredRow(UUID employeeId, String externalId, long hrVersion, long hrDigest,
                        EmployeeStatus status, DeactivatedBy deactivatedBy, Instant hrSourceTs) {

    public boolean isActive() {
        return status == EmployeeStatus.ACTIVE;
    }
}
