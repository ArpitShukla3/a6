package com.moveinsync.roster.domain.validation;

import java.time.Instant;
import java.util.Set;

/** Raw, unvalidated record as it arrived (API event or snapshot row). */
public record IncomingRecord(RecordType type,
                             String externalId,
                             Long version,
                             Instant occurredAt,
                             String name,
                             String email,
                             String phone,
                             String managerExternalId,
                             String officeCode,
                             String shiftCode,
                             Set<String> presentTransportFields) {

    @Override
    public String toString() {
        return "IncomingRecord[type=" + type + ", ext=" + externalId + ", v=" + version + ", pii=<redacted>]";
    }
}
