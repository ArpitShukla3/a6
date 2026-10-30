package com.moveinsync.roster.domain.event;

import com.moveinsync.roster.domain.model.EmployeeState;
import com.moveinsync.roster.domain.model.EmployeeStatus;
import com.moveinsync.roster.domain.model.HrProfile;

import java.time.Instant;

/**
 * A row of the nightly export. The export lists active employees, so a row at a newer version
 * than stored means "active with these fields". occurredAt is the export's as-of time.
 */
public final class SnapshotRowEvent extends RosterEvent {

    private final HrProfile hr;

    public SnapshotRowEvent(String externalId, long version, Instant asOf, HrProfile hr) {
        super(externalId, version, asOf);
        this.hr = hr;
    }

    @Override
    public EventSource source() {
        return EventSource.SNAPSHOT;
    }

    @Override
    public Transition transition(EmployeeState current) {
        return new Transition(hr, EmployeeStatus.ACTIVE, null);
    }
}
