package com.moveinsync.roster.domain.policy;

import com.moveinsync.roster.domain.event.RosterEvent;
import com.moveinsync.roster.domain.event.Transition;
import com.moveinsync.roster.domain.model.EmployeeState;
import com.moveinsync.roster.domain.model.EmployeeStatus;
import com.moveinsync.roster.domain.model.HrProfile;
import com.moveinsync.roster.domain.model.RecordHasher;
import com.moveinsync.roster.domain.model.TenantId;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds the new employee state for an applied HR event. Only HR-owned fields are taken from
 * the event; transport-owned fields are always copied from the current state.
 */
public final class EmployeeMerger {

    private final RecordHasher hasher;

    public EmployeeMerger(RecordHasher hasher) {
        this.hasher = hasher;
    }

    public MergeResult merge(TenantId tenant, EmployeeState current, RosterEvent event, Transition t) {
        HrProfile hr = t.hr();
        long digest = hasher.digest(event.externalId(), hr);
        if (current == null) {
            EmployeeState created = new EmployeeState(UUID.randomUUID(), tenant, event.externalId(), hr,
                    t.status(), t.deactivatedBy(), event.version(), digest, event.occurredAt(),
                    hasher.bucket(event.externalId()), null, null, 0, false);
            ChangeKind kind = t.status() == EmployeeStatus.ACTIVE ? ChangeKind.CREATED : ChangeKind.DEACTIVATED;
            return new MergeResult(created, kind, changedHrFields(HrProfile.EMPTY, hr));
        }

        boolean officeChanged = !hr.sameOffice(current.hr()) && current.hr().officeCode() != null;
        boolean needsReview = current.pickupNeedsReview() || (officeChanged && current.pickupPointId() != null);

        EmployeeState next = new EmployeeState(current.employeeId(), tenant, current.externalId(), hr,
                t.status(), t.deactivatedBy(), event.version(), digest, event.occurredAt(), current.bucket(),
                current.pickupPointId(), current.specialAssistance(), current.adminVersion(), needsReview);

        List<String> changed = changedHrFields(current.hr(), hr);
        if (t.status() != current.status()) {
            changed.add("status");
        }
        if (needsReview && !current.pickupNeedsReview()) {
            changed.add("pickupNeedsReview");
        }
        return new MergeResult(next, kindOf(current.status(), t.status()), changed);
    }

    /** Reconciliation deactivation: status only. HR fields, HR version and digest stay as they are. */
    public EmployeeState deactivateByReconciliation(EmployeeState current) {
        return new EmployeeState(current.employeeId(), current.tenantId(), current.externalId(), current.hr(),
                EmployeeStatus.INACTIVE, com.moveinsync.roster.domain.model.DeactivatedBy.RECONCILIATION,
                current.hrVersion(), current.hrDigest(), current.hrSourceTs(), current.bucket(),
                current.pickupPointId(), current.specialAssistance(), current.adminVersion(), current.pickupNeedsReview());
    }

    private static ChangeKind kindOf(EmployeeStatus before, EmployeeStatus after) {
        if (before == EmployeeStatus.ACTIVE && after == EmployeeStatus.INACTIVE) {
            return ChangeKind.DEACTIVATED;
        }
        if (before == EmployeeStatus.INACTIVE && after == EmployeeStatus.ACTIVE) {
            return ChangeKind.REACTIVATED;
        }
        return ChangeKind.UPDATED;
    }

    static List<String> changedHrFields(HrProfile a, HrProfile b) {
        List<String> out = new ArrayList<>();
        if (!Objects.equals(a.name(), b.name())) out.add("name");
        if (!Objects.equals(a.email(), b.email())) out.add("email");
        if (!Objects.equals(a.phone(), b.phone())) out.add("phone");
        if (!Objects.equals(a.managerExternalId(), b.managerExternalId())) out.add("managerExternalId");
        if (!Objects.equals(a.officeCode(), b.officeCode())) out.add("officeCode");
        if (!Objects.equals(a.shiftCode(), b.shiftCode())) out.add("shiftCode");
        return out;
    }
}
