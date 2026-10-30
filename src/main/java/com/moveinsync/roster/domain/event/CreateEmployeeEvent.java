package com.moveinsync.roster.domain.event;

import com.moveinsync.roster.domain.model.EmployeeState;
import com.moveinsync.roster.domain.model.EmployeeStatus;
import com.moveinsync.roster.domain.model.HrProfile;

import java.time.Instant;

/** New joiner, or a rehire of someone previously deactivated. Always results in ACTIVE. */
public final class CreateEmployeeEvent extends RosterEvent {

    private final HrProfile hr;

    public CreateEmployeeEvent(String externalId, long version, Instant occurredAt, HrProfile hr) {
        super(externalId, version, occurredAt);
        this.hr = hr;
    }

    @Override
    public EventSource source() {
        return EventSource.API;
    }

    @Override
    public Transition transition(EmployeeState current) {
        return new Transition(hr, EmployeeStatus.ACTIVE, null);
    }
}
