package com.moveinsync.roster.domain.event;

import com.moveinsync.roster.domain.model.DeactivatedBy;
import com.moveinsync.roster.domain.model.EmployeeState;

import java.time.Instant;
import java.util.Objects;

/**
 * One change from the HR system for one employee. Subclasses decide what HR-side state the
 * event leads to; the ordering rules ({@link com.moveinsync.roster.domain.policy.VersionGate})
 * are the same for all of them.
 */
public abstract sealed class RosterEvent
        permits CreateEmployeeEvent, UpdateEmployeeEvent, DeactivateEmployeeEvent, SnapshotRowEvent {

    private final String externalId;
    private final long version;
    private final Instant occurredAt;

    protected RosterEvent(String externalId, long version, Instant occurredAt) {
        this.externalId = Objects.requireNonNull(externalId);
        this.version = version;
        this.occurredAt = Objects.requireNonNull(occurredAt);
    }

    public String externalId() {
        return externalId;
    }

    public long version() {
        return version;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public abstract EventSource source();

    /** Target HR-side state given the current row (null if the employee is unknown). */
    public abstract Transition transition(EmployeeState current);

    /**
     * Whether this event, at the same version as stored, undoes a deactivation made by the
     * nightly reconciliation. Seeing the employee in a feed again is newer evidence than
     * their absence from a file.
     */
    public boolean revivesReconciliationDeactivation() {
        return true;
    }

    protected static boolean deactivatedByReconciliation(EmployeeState current) {
        return current != null && current.deactivatedBy() == DeactivatedBy.RECONCILIATION;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[ext=" + externalId + ", v=" + version + "]";
    }
}
