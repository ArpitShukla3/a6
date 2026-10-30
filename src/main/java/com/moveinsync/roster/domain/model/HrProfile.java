package com.moveinsync.roster.domain.model;

import java.util.Objects;

/**
 * The HR-owned fields of an employee. Values are already validated and normalised
 * (phone in E.164, email lower-case). {@link #toString()} never prints personal data.
 */
public record HrProfile(String name,
                        String email,
                        String phone,
                        String managerExternalId,
                        String officeCode,
                        String shiftCode) {

    public static final HrProfile EMPTY = new HrProfile(null, null, null, null, null, null);

    /** Stable representation used for hashing. Field separator and null marker can't appear in input. */
    public String canonical() {
        return String.join("\u001F",
                c(name), c(email), c(phone), c(managerExternalId), c(officeCode), c(shiftCode));
    }

    private static String c(String v) {
        return v == null ? "\u0000" : v;
    }

    public boolean sameOffice(HrProfile other) {
        return other != null && Objects.equals(officeCode, other.officeCode);
    }

    @Override
    public String toString() {
        return "HrProfile[office=" + officeCode + ", shift=" + shiftCode + ", pii=<redacted>]";
    }
}
