package com.moveinsync.roster.domain.event;

import com.moveinsync.roster.domain.model.EmployeeState;
import com.moveinsync.roster.domain.model.EmployeeStatus;
import com.moveinsync.roster.domain.model.HrProfile;

import java.time.Instant;

/**
 * Transfer, shift change, manager change, ... Carries the full set of HR fields (snapshot
 * semantics), so skipping a stale update never loses data. Does not change status, except
 * that it undoes a reconciliation deactivation. An unknown employee is created ACTIVE.
 */
public final class UpdateEmployeeEvent extends RosterEvent {

    private final HrProfile hr;

    public UpdateEmployeeEvent(String externalId, long version, Instant occurredAt, HrProfile hr) {
        super(externalId, version, occurredAt);
        this.hr = hr;
    }

    @Override
    public EventSource source() {
        return EventSource.API;
    }

    @Override
    public Transition transition(EmployeeState current) {
        if (current == null || deactivatedByReconciliation(current)) {
            return new Transition(hr, EmployeeStatus.ACTIVE, null);
        }
        return new Transition(hr, current.status(), current.deactivatedBy());
    }
}
