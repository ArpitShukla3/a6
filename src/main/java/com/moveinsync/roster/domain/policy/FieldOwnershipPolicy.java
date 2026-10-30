package com.moveinsync.roster.domain.policy;

import java.util.List;
import java.util.Set;

/**
 * Who may write which field. Conflict rule:
 * <ol>
 *   <li>HR owns name, email, phone, manager, office, shift and status; only HR events change them.</li>
 *   <li>The transport admin owns pickup point and special assistance; only the transport API changes them.</li>
 *   <li>Transport fields found in an HR payload are dropped (and reported as a warning); the rest
 *       of the record is still applied.</li>
 *   <li>When HR changes the office of an employee who has a pickup point, the pickup point is
 *       kept and flagged for review. HR never clears it.</li>
 * </ol>
 */
public final class FieldOwnershipPolicy {

    public static final Set<String> HR_FIELDS =
            Set.of("name", "email", "phone", "managerExternalId", "officeCode", "shiftCode", "status");
    public static final Set<String> TRANSPORT_FIELDS = Set.of("pickupPointId", "specialAssistance");

    /** Transport-owned field names present in an HR payload, in a stable order. */
    public List<String> violationsInHrPayload(Set<String> presentFields) {
        return TRANSPORT_FIELDS.stream().filter(presentFields::contains).sorted().toList();
    }
}
