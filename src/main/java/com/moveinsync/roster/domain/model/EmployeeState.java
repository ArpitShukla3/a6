package com.moveinsync.roster.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Snapshot of one employee row. Immutable: changes produce a new instance through
 * {@link com.moveinsync.roster.domain.policy.EmployeeMerger} (HR side) or
 * {@link #withTransport(String, String)} (transport-admin side), so neither side can write the
 * other side's fields.
 */
public record EmployeeState(UUID employeeId,
                            TenantId tenantId,
                            String externalId,
                            HrProfile hr,
                            EmployeeStatus status,
                            DeactivatedBy deactivatedBy,
                            long hrVersion,
                            long hrDigest,
                            Instant hrSourceTs,
                            int bucket,
                            String pickupPointId,
                            String specialAssistance,
                            long adminVersion,
                            boolean pickupNeedsReview) {

    public boolean isActive() {
        return status == EmployeeStatus.ACTIVE;
    }

    /** Transport-admin edit. HR fields and HR version are carried over untouched. */
    public EmployeeState withTransport(String newPickupPointId, String newSpecialAssistance) {
        return new EmployeeState(employeeId, tenantId, externalId, hr, status, deactivatedBy,
                hrVersion, hrDigest, hrSourceTs, bucket,
                newPickupPointId, newSpecialAssistance, adminVersion + 1, false);
    }

    @Override
    public String toString() {
        return "Employee[id=" + employeeId + ", ext=" + externalId + ", status=" + status
                + ", hrVersion=" + hrVersion + ", adminVersion=" + adminVersion + "]";
    }
}
