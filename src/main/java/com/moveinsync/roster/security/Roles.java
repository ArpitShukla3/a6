package com.moveinsync.roster.security;

import java.util.Set;

public final class Roles {
    /** Machine client of the corporate HR system: pushes events and snapshots. */
    public static final String HR_SYSTEM = "HR_SYSTEM";
    /** Edits transport-owned fields, books cabs. */
    public static final String TRANSPORT_ADMIN = "TRANSPORT_ADMIN";
    /** On-call / ops: reconciliation approvals, offices. */
    public static final String OPERATOR = "OPERATOR";
    /** An employee: may read only their own record and bookings. */
    public static final String EMPLOYEE = "EMPLOYEE";

    public static final Set<String> ALL = Set.of(HR_SYSTEM, TRANSPORT_ADMIN, OPERATOR, EMPLOYEE);

    private Roles() {
    }
}
