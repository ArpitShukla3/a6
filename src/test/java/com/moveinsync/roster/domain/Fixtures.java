package com.moveinsync.roster.domain;

import com.moveinsync.roster.domain.model.DeactivatedBy;
import com.moveinsync.roster.domain.model.EmployeeState;
import com.moveinsync.roster.domain.model.EmployeeStatus;
import com.moveinsync.roster.domain.model.HrProfile;
import com.moveinsync.roster.domain.model.RecordHasher;
import com.moveinsync.roster.domain.model.TenantId;

import java.time.Instant;
import java.util.UUID;

public final class Fixtures {
    public static final TenantId TENANT = TenantId.of("11111111-1111-1111-1111-111111111111");
    public static final RecordHasher HASHER = new RecordHasher(64);
    public static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    public static HrProfile hr(String office, String shift) {
        return new HrProfile("Asha Rao", "asha@acme.test", "+919876543210", "M1", office, shift);
    }

    public static EmployeeState state(String ext, long version, HrProfile hr, EmployeeStatus status, DeactivatedBy by) {
        return new EmployeeState(UUID.nameUUIDFromBytes(ext.getBytes()), TENANT, ext, hr, status, by, version,
                HASHER.digest(ext, hr), T0, HASHER.bucket(ext), "PP-1", "WHEELCHAIR", 3, false);
    }

    public static EmployeeState active(String ext, long version, HrProfile hr) {
        return state(ext, version, hr, EmployeeStatus.ACTIVE, null);
    }

    private Fixtures() {
    }
}
