package com.moveinsync.roster.domain.event;

import com.moveinsync.roster.domain.model.DeactivatedBy;
import com.moveinsync.roster.domain.model.EmployeeStatus;
import com.moveinsync.roster.domain.model.HrProfile;

/** The HR-side state an event wants to move the employee to. */
public record Transition(HrProfile hr, EmployeeStatus status, DeactivatedBy deactivatedBy) {
}
