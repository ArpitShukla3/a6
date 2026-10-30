package com.moveinsync.roster.api.dto;

import com.moveinsync.roster.domain.model.EmployeeState;

import java.util.UUID;

public record EmployeeView(UUID employeeId, String externalId, String name, String email, String phone,
                           String managerExternalId, String officeCode, String shiftCode, String status,
                           String deactivatedBy, long hrVersion, String pickupPointId, String specialAssistance,
                           long adminVersion, boolean pickupNeedsReview) {

    public static EmployeeView of(EmployeeState e) {
        return new EmployeeView(e.employeeId(), e.externalId(), e.hr().name(), e.hr().email(), e.hr().phone(),
                e.hr().managerExternalId(), e.hr().officeCode(), e.hr().shiftCode(), e.status().name(),
                e.deactivatedBy() == null ? null : e.deactivatedBy().name(), e.hrVersion(),
                e.pickupPointId(), e.specialAssistance(), e.adminVersion(), e.pickupNeedsReview());
    }

    @Override
    public String toString() {
        return "EmployeeView[id=" + employeeId + ", pii=<redacted>]";
    }
}
