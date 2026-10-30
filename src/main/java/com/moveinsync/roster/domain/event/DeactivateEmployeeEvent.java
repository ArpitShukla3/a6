package com.moveinsync.roster.domain.event;

import com.moveinsync.roster.domain.model.DeactivatedBy;
import com.moveinsync.roster.domain.model.EmployeeState;
import com.moveinsync.roster.domain.model.EmployeeStatus;
import com.moveinsync.roster.domain.model.HrProfile;

import java.time.Instant;

/**
 * Exit. HR fields are kept. For an unknown employee a tombstone is stored, so a late,
 * older CREATE for the same person is recognised as stale.
 */
public final class DeactivateEmployeeEvent extends RosterEvent {

    public DeactivateEmployeeEvent(String externalId, long version, Instant occurredAt) {
        super(externalId, version, occurredAt);
    }

    @Override
    public EventSource source() {
        return EventSource.API;
    }

    @Override
    public Transition transition(EmployeeState current) {
        HrProfile hr = current == null ? HrProfile.EMPTY : current.hr();
        return new Transition(hr, EmployeeStatus.INACTIVE, DeactivatedBy.HR);
    }

    @Override
    public boolean revivesReconciliationDeactivation() {
        return false;
    }
}
